/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.dispatch._
import se.scalablesolutions.akka.config.Config.config
import se.scalablesolutions.akka.config.{AllForOneStrategy, OneForOneStrategy, FaultHandlingStrategy}
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.stm.global._
import se.scalablesolutions.akka.stm.TransactionManagement._
import se.scalablesolutions.akka.stm.{TransactionManagement, TransactionSetAbortedException}
import se.scalablesolutions.akka.remote.protocol.RemoteProtocol._
import se.scalablesolutions.akka.remote.{RemoteNode, RemoteServer, RemoteClient, MessageSerializer, RemoteRequestProtocolIdFactory}
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.util.{HashCode, Logging, UUID, ReentrantGuard}
import RemoteActorSerialization._

import org.multiverse.api.ThreadLocalTransaction._
import org.multiverse.commitbarriers.CountDownCommitBarrier
import org.multiverse.api.exceptions.DeadTransactionException

import java.net.InetSocketAddress
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.{Map => JMap}
import java.lang.reflect.Field

import jsr166x.{Deque, ConcurrentLinkedDeque}

import com.google.protobuf.ByteString

/**
 * ActorRef is an immutable and serializable handle to an Actor.
 * <p/>
 * Create an ActorRef for an Actor by using the factory method on the Actor object.
 * <p/>
 * Here is an example on how to create an actor with a default constructor.
 * <pre>
 *   import Actor._
 *
 *   val actor = actorOf[MyActor]
 *   actor.start
 *   actor ! message
 *   actor.stop
 * </pre>
 *
 * You can also create and start actors like this:
 * <pre>
 *   val actor = actorOf[MyActor].start
 * </pre>
 *
 * Here is an example on how to create an actor with a non-default constructor.
 * <pre>
 *   import Actor._
 *
 *   val actor = actorOf(new MyActor(...))
 *   actor.start
 *   actor ! message
 *   actor.stop
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait ActorRef extends TransactionManagement with java.lang.Comparable[ActorRef] {

  // Only mutable for RemoteServer in order to maintain identity across nodes
  @volatile protected[akka] var _uuid = UUID.newUuid.toString
  @volatile protected[this] var _isRunning = false
  @volatile protected[this] var _isShutDown = false
  @volatile protected[akka] var _isBeingRestarted = false
  @volatile protected[akka] var _homeAddress = new InetSocketAddress(RemoteServer.HOSTNAME, RemoteServer.PORT)
  @volatile protected[akka] var _timeoutActor: Option[ActorRef] = None
  @volatile protected[akka] var startOnCreation = false
  @volatile protected[akka] var registeredInRemoteNodeDuringSerialization = false
  protected[this] val guard = new ReentrantGuard

  /**
   * User overridable callback/setting.
   * <p/>
   * Identifier for actor, does not have to be a unique one. Default is the 'uuid'.
   * <p/>
   * This field is used for logging, AspectRegistry.actorsFor(id), identifier for remote
   * actor in RemoteServer etc.But also as the identifier for persistence, which means
   * that you can use a custom name to be able to retrieve the "correct" persisted state
   * upon restart, remote restart etc.
   */
  @volatile var id: String = _uuid

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the default timeout for '!!' and '!!!' invocations,
   * e.g. the timeout for the future returned by the call to '!!' and '!!!'.
   */
  @volatile var timeout: Long = Actor.TIMEOUT

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the default timeout for an initial receive invocation.
   * When specified, the receive function should be able to handle a 'ReceiveTimeout' message.
   */
  @volatile var receiveTimeout: Option[Long] = None

  /**
   * User overridable callback/setting.
   *
   * <p/>
   * Set trapExit to the list of exception classes that the actor should be able to trap
   * from the actor it is supervising. When the supervising actor throws these exceptions
   * then they will trigger a restart.
   * <p/>
   *
   * Trap no exceptions:
   * <pre>
   * trapExit = Nil
   * </pre>
   *
   * Trap all exceptions:
   * <pre>
   * trapExit = List(classOf[Throwable])
   * </pre>
   *
   * Trap specific exceptions only:
   * <pre>
   * trapExit = List(classOf[MyApplicationException], classOf[MyApplicationError])
   * </pre>
   */
  @volatile var trapExit: List[Class[_ <: Throwable]] = Nil

  /**
   * User overridable callback/setting.
   * <p/>
   * If 'trapExit' is set for the actor to act as supervisor, then a faultHandler must be defined.
   * <p/>
   * Can be one of:
   * <pre>
   *  faultHandler = Some(AllForOneStrategy(maxNrOfRetries, withinTimeRange))
   * </pre>
   * Or:
   * <pre>
   *  faultHandler = Some(OneForOneStrategy(maxNrOfRetries, withinTimeRange))
   * </pre>
   */
  @volatile var faultHandler: Option[FaultHandlingStrategy] = None

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the life-cycle for a supervised actor.
   */
  @volatile var lifeCycle: Option[LifeCycle] = None

  /**
   * The default dispatcher is the <tt>Dispatchers.globalExecutorBasedEventDrivenDispatcher</tt>.
   * This means that all actors will share the same event-driven executor based dispatcher.
   * <p/>
   * You can override it so it fits the specific use-case that the actor is used for.
   * See the <tt>se.scalablesolutions.akka.dispatch.Dispatchers</tt> class for the different
   * dispatchers available.
   * <p/>
   * The default is also that all actors that are created and spawned from within this actor
   * is sharing the same dispatcher as its creator.
   */
  @volatile private[akka] var _dispatcher: MessageDispatcher = Dispatchers.globalExecutorBasedEventDrivenDispatcher

  /**
   * Holds the hot swapped partial function.
   */
  @volatile protected[akka] var hotswap: Option[PartialFunction[Any, Unit]] = None // FIXME: _hotswap should be a stack

  /**
   * User overridable callback/setting.
   * <p/>
   * Set to true if messages should have REQUIRES_NEW semantics, e.g. a new transaction should
   * start if there is no one running, else it joins the existing transaction.
   */
  @volatile protected[akka] var isTransactor = false

  /**
   * Configuration for TransactionFactory. User overridable.
   */
  @volatile protected[akka] var _transactionConfig: TransactionConfig = DefaultGlobalTransactionConfig

  /**
   * TransactionFactory to be used for atomic when isTransactor. Configuration is overridable.
   */
  @volatile private[akka] var _transactionFactory: Option[TransactionFactory] = None

  /**
   * This lock ensures thread safety in the dispatching: only one message can
   * be dispatched at once on the actor.
   */
  protected[akka] val dispatcherLock = new ReentrantLock

  /**
   * This is a reference to the message currently being processed by the actor
   */
  protected[akka] var _currentMessage: Option[MessageInvocation] = None

  protected[akka] def currentMessage_=(msg: Option[MessageInvocation]) = guard.withGuard { _currentMessage = msg }
  protected[akka] def currentMessage = guard.withGuard { _currentMessage }
  
  /** comparison only takes uuid into account
   */
  def compareTo(other: ActorRef) = this.uuid.compareTo(other.uuid)

  /**
   * Returns the uuid for the actor.
   */
  def uuid = _uuid

  /**
   * The reference sender Actor of the last received message.
   * Is defined if the message was sent from another Actor, else None.
   */
  def sender: Option[ActorRef] = {
    // Five lines of map-performance-avoidance, could be just: currentMessage map { _.sender }
    val msg = currentMessage
    if(msg.isEmpty) None
    else msg.get.sender
  }

  /**
   * The reference sender future of the last received message.
   * Is defined if the message was sent with sent with '!!' or '!!!', else None.
   */
  def senderFuture: Option[CompletableFuture[Any]] = {
    // Five lines of map-performance-avoidance, could be just: currentMessage map { _.senderFuture }
    val msg = currentMessage
    if(msg.isEmpty) None
    else msg.get.senderFuture
  }

  /**
   * Is the actor being restarted?
   */
  def isBeingRestarted: Boolean = _isBeingRestarted

  /**
   * Is the actor running?
   */
  def isRunning: Boolean = _isRunning

  /**
   * Is the actor shut down?
   */
  def isShutdown: Boolean = _isShutDown

  /**
   * Is the actor able to handle the message passed in as arguments?
   */
  def isDefinedAt(message: Any): Boolean = actor.isDefinedAt(message)

  /**
   * Only for internal use. UUID is effectively final.
   */
  protected[akka] def uuid_=(uid: String) = _uuid = uid

  /**
   * Sends a one-way asynchronous message. E.g. fire-and-forget semantics.
   * <p/>
   *
   * If invoked from within an actor then the actor reference is implicitly passed on as the implicit 'sender' argument.
   * <p/>
   *
   * This actor 'sender' reference is then available in the receiving actor in the 'sender' member variable,
   * if invoked from within an Actor. If not then no sender is available.
   * <pre>
   *   actor ! message
   * </pre>
   * <p/>
   */
  def !(message: Any)(implicit sender: Option[ActorRef] = None) = {
    if (isRunning) postMessageToMailbox(message, sender)
    else throw new ActorInitializationException(
      "Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Sends a message asynchronously and waits on a future for a reply message.
   * <p/>
   * It waits on the reply either until it receives it (in the form of <code>Some(replyMessage)</code>)
   * or until the timeout expires (which will return None). E.g. send-and-receive-eventually semantics.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use '!' together with the 'sender' member field to
   * implement request/response message exchanges.
   * If you are sending messages using <code>!!</code> then you <b>have to</b> use <code>self.reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def !!(message: Any, timeout: Long = this.timeout)(implicit sender: Option[ActorRef] = None): Option[Any] = {
    if (isRunning) {
      val future = postMessageToMailboxAndCreateFutureResultWithTimeout[Any](message, timeout, sender, None)
      val isTypedActor = message.isInstanceOf[Invocation]
      if (isTypedActor && message.asInstanceOf[Invocation].isVoid) {
        future.asInstanceOf[CompletableFuture[Option[_]]].completeWithResult(None)
      }
      try {
        future.await
      } catch {
        case e: FutureTimeoutException =>
          if (isTypedActor) throw e
          else None
      }
      if (future.exception.isDefined) throw future.exception.get._2
      else future.result
    } else throw new ActorInitializationException(
        "Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Sends a message asynchronously returns a future holding the eventual reply message.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use '!' together with the 'sender' member field to
   * implement request/response message exchanges.
   * If you are sending messages using <code>!!!</code> then you <b>have to</b> use <code>self.reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def !!![T](message: Any)(implicit sender: Option[ActorRef] = None): Future[T] = {
    if (isRunning) postMessageToMailboxAndCreateFutureResultWithTimeout[T](message, timeout, sender, None)
    else throw new ActorInitializationException(
      "Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Forwards the message and passes the original sender actor as the sender.
   * <p/>
   * Works with '!', '!!' and '!!!'.
   */
  def forward(message: Any)(implicit sender: Some[ActorRef]) = {
    if (isRunning) {
      if (sender.get.senderFuture.isDefined) postMessageToMailboxAndCreateFutureResultWithTimeout(
        message, timeout, sender.get.sender, sender.get.senderFuture)
      else if (sender.get.sender.isDefined) postMessageToMailbox(message, Some(sender.get.sender.get))
      else throw new IllegalActorStateException("Can't forward message when initial sender is not an actor")
    } else throw new ActorInitializationException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Use <code>self.reply(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Throws an IllegalStateException if unable to determine what to reply to.
   */
  def reply(message: Any) = if(!reply_?(message)) throw new IllegalActorStateException(
    "\n\tNo sender in scope, can't reply. " +
    "\n\tYou have probably: " +
    "\n\t\t1. Sent a message to an Actor from an instance that is NOT an Actor." +
    "\n\t\t2. Invoked a method on an TypedActor from an instance NOT an TypedActor." +
    "\n\tElse you might want to use 'reply_?' which returns Boolean(true) if succes and Boolean(false) if no sender in scope")

  /**
   * Use <code>reply_?(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Returns true if reply was sent, and false if unable to determine what to reply to.
   */
  def reply_?(message: Any): Boolean = {
    if (senderFuture.isDefined) {
      senderFuture.get completeWithResult message
      true
    } else if (sender.isDefined) {
      sender.get ! message
      true
    } else false
  }

  /**
   * Returns the class for the Actor instance that is managed by the ActorRef.
   */
  def actorClass: Class[_ <: Actor]

  /**
   * Returns the class name for the Actor instance that is managed by the ActorRef.
   */
  def actorClassName: String

  /**
   * Sets the dispatcher for this actor. Needs to be invoked before the actor is started.
   */
  def dispatcher_=(md: MessageDispatcher): Unit

  /**
   * Get the dispatcher for this actor.
   */
  def dispatcher: MessageDispatcher

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(hostname: String, port: Int): Unit

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(address: InetSocketAddress): Unit

  /**
   * Invoking 'makeTransactionRequired' means that the actor will **start** a new transaction if non exists.
   * However, it will always participate in an existing transaction.
   */
  def makeTransactionRequired(): Unit

  /**
   * Sets the transaction configuration for this actor. Needs to be invoked before the actor is started.
   */
  def transactionConfig_=(config: TransactionConfig): Unit

  /**
   * Get the transaction configuration for this actor.
   */
  def transactionConfig: TransactionConfig

  /**
   * Returns the home address and port for this actor.
   */
  def homeAddress: InetSocketAddress = _homeAddress

  /**
   * Set the home address and port for this actor.
   */
  def homeAddress_=(hostnameAndPort: Tuple2[String, Int]): Unit =
    homeAddress_=(new InetSocketAddress(hostnameAndPort._1, hostnameAndPort._2))

  /**
   * Set the home address and port for this actor.
   */
  def homeAddress_=(address: InetSocketAddress): Unit

  /**
   * Returns the remote address for the actor, if any, else None.
   */
  def remoteAddress: Option[InetSocketAddress]
  protected[akka] def remoteAddress_=(addr: Option[InetSocketAddress]): Unit

  /**
   * Starts up the actor and its message queue.
   */
  def start(): ActorRef

  /**
   * Shuts down the actor its dispatcher and message queue.
   * Alias for 'stop'.
   */
  def exit() = stop()

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop(): Unit

  /**
   * Links an other actor to this actor. Links are unidirectional and means that a the linking actor will
   * receive a notification if the linked actor has crashed.
   * <p/>
   * If the 'trapExit' member field has been set to at contain at least one exception class then it will
   * 'trap' these exceptions and automatically restart the linked actors according to the restart strategy
   * defined by the 'faultHandler'.
   */
  def link(actorRef: ActorRef): Unit

  /**
   * Unlink the actor.
   */
  def unlink(actorRef: ActorRef): Unit

  /**
   * Atomically start and link an actor.
   */
  def startLink(actorRef: ActorRef): Unit

  /**
   * Atomically start, link and make an actor remote.
   */
  def startLinkRemote(actorRef: ActorRef, hostname: String, port: Int): Unit

  /**
   * Atomically create (from actor class) and start an actor.
   */
  def spawn[T <: Actor : Manifest]: ActorRef

  /**
   * Atomically create (from actor class), start and make an actor remote.
   */
  def spawnRemote[T <: Actor: Manifest](hostname: String, port: Int): ActorRef

  /**
   * Atomically create (from actor class), start and link an actor.
   */
  def spawnLink[T <: Actor: Manifest]: ActorRef

  /**
   * Atomically create (from actor class), start, link and make an actor remote.
   */
  def spawnLinkRemote[T <: Actor : Manifest](hostname: String, port: Int): ActorRef

  /**
   * Returns the mailbox size.
   */
  def mailboxSize = dispatcher.mailboxSize(this)

  /**
   * Returns the supervisor, if there is one.
   */
  def supervisor: Option[ActorRef]

  /**
   * Shuts down and removes all linked actors.
   */
  def shutdownLinkedActors(): Unit

  protected[akka] def invoke(messageHandle: MessageInvocation): Unit

  protected[akka] def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
      message: Any,
      timeout: Long,
      senderOption: Option[ActorRef],
      senderFuture: Option[CompletableFuture[T]]): CompletableFuture[T]

  protected[this] def actorInstance: AtomicReference[Actor]

  protected[akka] def actor: Actor = actorInstance.get

  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit

  protected[akka] def mailbox: AnyRef
  protected[akka] def mailbox_=(value: AnyRef): AnyRef

  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable): Unit

  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Int, withinTimeRange: Int): Unit

  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Int, withinTimeRange: Int): Unit

  protected[akka] def registerSupervisorAsRemoteActor: Option[String]

  protected[akka] def linkedActors: JMap[String, ActorRef]

  protected[akka] def linkedActorsAsList: List[ActorRef]

  override def hashCode: Int = HashCode.hash(HashCode.SEED, uuid)

  override def equals(that: Any): Boolean = {
    that != null &&
    that.isInstanceOf[ActorRef] &&
    that.asInstanceOf[ActorRef].uuid == uuid
  }

  override def toString = "Actor[" + id + ":" + uuid + "]"

  protected[akka] def checkReceiveTimeout = {
    cancelReceiveTimeout
    receiveTimeout.foreach { time =>
      log.debug("Scheduling timeout for %s", this)
      _timeoutActor = Some(Scheduler.scheduleOnce(this, ReceiveTimeout, time, TimeUnit.MILLISECONDS))
    }
  }

  protected[akka] def cancelReceiveTimeout = _timeoutActor.foreach { timeoutActor =>
    if (timeoutActor.isRunning) Scheduler.unschedule(timeoutActor)
    _timeoutActor = None
    log.debug("Timeout canceled for %s", this)
  }
}

/**
 * Local (serializable) ActorRef that is used when referencing the Actor on its "home" node.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class LocalActorRef private[akka](
  private[this] var actorFactory: Either[Option[Class[_ <: Actor]], Option[() => Actor]] = Left(None))
  extends ActorRef {

  @volatile private[akka] var _remoteAddress: Option[InetSocketAddress] = None // only mutable to maintain identity across nodes
  @volatile private[akka] var _linkedActors: Option[ConcurrentHashMap[String, ActorRef]] = None
  @volatile private[akka] var _supervisor: Option[ActorRef] = None
  @volatile private var isInInitialization = false
  @volatile private var runActorInitialization = false
  @volatile private var isDeserialized = false
  @volatile private var loader: Option[ClassLoader] = None
  @volatile private var maxNrOfRetriesCount: Int = 0
  @volatile private var restartsWithinTimeRangeTimestamp: Long = 0L
  @volatile private var _mailbox: AnyRef = _

  protected[this] val actorInstance = guard.withGuard { new AtomicReference[Actor](newActor) }

  // Needed to be able to null out the 'val self: ActorRef' member variables to make the Actor
  // instance elegible for garbage collection
  private val actorSelfFields = findActorSelfField(actor.getClass)

  if (runActorInitialization && !isDeserialized) initializeActorInstance

  private[akka] def this(clazz: Class[_ <: Actor]) = this(Left(Some(clazz)))
  private[akka] def this(factory: () => Actor) =     this(Right(Some(factory)))

  // used only for deserialization
  private[akka] def this(__uuid: String,
                         __id: String,
                         __actorClassName: String,
                         __actorBytes: Array[Byte],
                         __hostname: String,
                         __port: Int,
                         __isTransactor: Boolean,
                         __timeout: Long,
                         __receiveTimeout: Option[Long],
                         __lifeCycle: Option[LifeCycle],
                         __supervisor: Option[ActorRef],
                         __hotswap: Option[PartialFunction[Any, Unit]],
                         __loader: ClassLoader,
                         __messages: List[RemoteRequestProtocol],
                         __format: Format[_ <: Actor]) = {
      this(() => {
        val actorClass = __loader.loadClass(__actorClassName)
        if (__format.isInstanceOf[SerializerBasedActorFormat[_]])
          __format.asInstanceOf[SerializerBasedActorFormat[_]]
                  .serializer
                  .fromBinary(__actorBytes, Some(actorClass)).asInstanceOf[Actor]
        else actorClass.newInstance.asInstanceOf[Actor]
      })
      loader = Some(__loader)
      isDeserialized = true
      _uuid = __uuid
      id = __id
      homeAddress = (__hostname, __port)
      isTransactor = __isTransactor
      timeout = __timeout
      receiveTimeout = __receiveTimeout
      lifeCycle = __lifeCycle
      _supervisor = __supervisor
      hotswap = __hotswap
      actorSelfFields._1.set(actor, this)
      actorSelfFields._2.set(actor, Some(this))
      actorSelfFields._3.set(actor, Some(this))
      start
      __messages.foreach(message => this ! MessageSerializer.deserialize(message.getMessage))
      checkReceiveTimeout
      ActorRegistry.register(this)
    }

  // ========= PUBLIC FUNCTIONS =========

  /**
   * Returns the class for the Actor instance that is managed by the ActorRef.
   */
  def actorClass: Class[_ <: Actor] = actor.getClass.asInstanceOf[Class[_ <: Actor]]

  /**
   * Returns the class name for the Actor instance that is managed by the ActorRef.
   */
  def actorClassName: String = actorClass.getName

  /**
   * Sets the dispatcher for this actor. Needs to be invoked before the actor is started.
   */
  def dispatcher_=(md: MessageDispatcher): Unit = {
    if (!isRunning || isBeingRestarted) _dispatcher = md
    else throw new ActorInitializationException(
      "Can not swap dispatcher for " + toString + " after it has been started")
  }

  /**
   * Get the dispatcher for this actor.
   */
  def dispatcher: MessageDispatcher = _dispatcher

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(hostname: String, port: Int): Unit =
    if (!isRunning || isBeingRestarted) makeRemote(new InetSocketAddress(hostname, port))
    else throw new ActorInitializationException(
      "Can't make a running actor remote. Make sure you call 'makeRemote' before 'start'.")

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(address: InetSocketAddress): Unit = guard.withGuard {
    if (!isRunning || isBeingRestarted) {
      _remoteAddress = Some(address)
      RemoteClient.register(address.getHostName, address.getPort, uuid)
      homeAddress = (RemoteServer.HOSTNAME, RemoteServer.PORT)
    } else throw new ActorInitializationException(
      "Can't make a running actor remote. Make sure you call 'makeRemote' before 'start'.")
  }

  /**
   * Invoking 'makeTransactionRequired' means that the actor will **start** a new transaction if non exists.
   * However, it will always participate in an existing transaction.
   */
  def makeTransactionRequired() = guard.withGuard {
    if (!isRunning || isBeingRestarted) isTransactor = true
    else throw new ActorInitializationException(
      "Can not make actor transaction required after it has been started")
  }

  /**
   * Sets the transaction configuration for this actor. Needs to be invoked before the actor is started.
   */
  def transactionConfig_=(config: TransactionConfig) = guard.withGuard {
    if (!isRunning || isBeingRestarted) _transactionConfig = config
    else throw new ActorInitializationException(
      "Cannot set transaction configuration for actor after it has been started")
  }

  /**
   * Get the transaction configuration for this actor.
   */
  def transactionConfig: TransactionConfig = _transactionConfig

  /**
   * Set the contact address for this actor. This is used for replying to messages
   * sent asynchronously when no reply channel exists.
   */
  def homeAddress_=(address: InetSocketAddress): Unit = _homeAddress = address

  /**
   * Returns the remote address for the actor, if any, else None.
   */
  def remoteAddress: Option[InetSocketAddress] = _remoteAddress
  protected[akka] def remoteAddress_=(addr: Option[InetSocketAddress]): Unit = _remoteAddress = addr

  /**
   * Starts up the actor and its message queue.
   */
  def start: ActorRef = guard.withGuard {
    if (isShutdown) throw new ActorStartException(
      "Can't restart an actor that has been shut down with 'stop' or 'exit'")
    if (!isRunning) {
      dispatcher.register(this)
      dispatcher.start
      if (isTransactor) {
        _transactionFactory = Some(TransactionFactory(_transactionConfig, id))
      }
      _isRunning = true
      if (!isInInitialization) initializeActorInstance
      else runActorInitialization = true
    }
    this
  }

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop() = guard.withGuard {
    if (isRunning) {
      cancelReceiveTimeout
      dispatcher.unregister(this)
      _transactionFactory = None
      _isRunning = false
      _isShutDown = true
      actor.shutdown
      ActorRegistry.unregister(this)
      remoteAddress.foreach(address => RemoteClient.unregister(
        address.getHostName, address.getPort, uuid))
      RemoteNode.unregister(this)
      nullOutActorRefReferencesFor(actorInstance.get)
    } //else if (isBeingRestarted) throw new ActorKilledException("Actor [" + toString + "] is being restarted.")
  }

  /**
   * Links an other actor to this actor. Links are unidirectional and means that a the linking actor will
   * receive a notification if the linked actor has crashed.
   * <p/>
   * If the 'trapExit' member field has been set to at contain at least one exception class then it will
   * 'trap' these exceptions and automatically restart the linked actors according to the restart strategy
   * defined by the 'faultHandler'.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def link(actorRef: ActorRef) = guard.withGuard {
    if (actorRef.supervisor.isDefined) throw new IllegalActorStateException(
      "Actor can only have one supervisor [" + actorRef + "], e.g. link(actor) fails")
    linkedActors.put(actorRef.uuid, actorRef)
    actorRef.supervisor = Some(this)
    Actor.log.debug("Linking actor [%s] to actor [%s]", actorRef, this)
  }

  /**
   * Unlink the actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def unlink(actorRef: ActorRef) = guard.withGuard {
    if (!linkedActors.containsKey(actorRef.uuid)) throw new IllegalActorStateException(
      "Actor [" + actorRef + "] is not a linked actor, can't unlink")
    linkedActors.remove(actorRef.uuid)
    actorRef.supervisor = None
    Actor.log.debug("Unlinking actor [%s] from actor [%s]", actorRef, this)
  }

  /**
   * Atomically start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def startLink(actorRef: ActorRef) = guard.withGuard {
    try {
      actorRef.start
    } finally {
      link(actorRef)
    }
  }

  /**
   * Atomically start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def startLinkRemote(actorRef: ActorRef, hostname: String, port: Int) = guard.withGuard {
    try {
      actorRef.makeRemote(hostname, port)
      actorRef.start
    } finally {
      link(actorRef)
    }
  }

  /**
   * Atomically create (from actor class) and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawn[T <: Actor : Manifest]: ActorRef = guard.withGuard {
    val actorRef = spawnButDoNotStart[T]
    actorRef.start
    actorRef
  }

  /**
   * Atomically create (from actor class), start and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnRemote[T <: Actor: Manifest](hostname: String, port: Int): ActorRef = guard.withGuard {
    val actor = spawnButDoNotStart[T]
    actor.makeRemote(hostname, port)
    actor.start
    actor
  }

  /**
   * Atomically create (from actor class), start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnLink[T <: Actor: Manifest]: ActorRef = guard.withGuard {
    val actor = spawnButDoNotStart[T]
    try {
      actor.start
    } finally {
      link(actor)
    }
    actor
  }

  /**
   * Atomically create (from actor class), start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnLinkRemote[T <: Actor : Manifest](hostname: String, port: Int): ActorRef = guard.withGuard {
    val actor = spawnButDoNotStart[T]
    try {
      actor.makeRemote(hostname, port)
      actor.start
    } finally {
      link(actor)
    }
  }

  /**
   * Returns the mailbox.
   */
  def mailbox: AnyRef = _mailbox

  protected[akka] def mailbox_=(value: AnyRef):AnyRef = { _mailbox = value; value }

  /**
   * Shuts down and removes all linked actors.
   */
  def shutdownLinkedActors(): Unit = {
    linkedActorsAsList.foreach(_.stop)
    linkedActors.clear
  }

  /**
   * Returns the supervisor, if there is one.
   */
  def supervisor: Option[ActorRef] = _supervisor

  // ========= AKKA PROTECTED FUNCTIONS =========

  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit = _supervisor = sup

  protected[akka] def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit = {
    joinTransaction(message)

    if (remoteAddress.isDefined) {
      RemoteClient.clientFor(remoteAddress.get).send[Any](
        createRemoteRequestProtocolBuilder(this, message, true, senderOption).build, None)
    } else {
      val invocation = new MessageInvocation(this, message, senderOption, None, transactionSet.get)
      invocation.send
    }
  }

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
      message: Any,
      timeout: Long,
      senderOption: Option[ActorRef],
      senderFuture: Option[CompletableFuture[T]]): CompletableFuture[T] = {
    joinTransaction(message)

    if (remoteAddress.isDefined) {
      val future = RemoteClient.clientFor(remoteAddress.get).send(
        createRemoteRequestProtocolBuilder(this, message, false, senderOption).build, senderFuture)
      if (future.isDefined) future.get
      else throw new IllegalActorStateException("Expected a future from remote call to actor " + toString)
    } else {
      val future = if (senderFuture.isDefined) senderFuture.get
                   else new DefaultCompletableFuture[T](timeout)
      val invocation = new MessageInvocation(
        this, message, senderOption, Some(future.asInstanceOf[CompletableFuture[Any]]), transactionSet.get)
      invocation.send
      future
    }
  }

  /**
   * Callback for the dispatcher. This is the ingle entry point to the user Actor implementation.
   */
  protected[akka] def invoke(messageHandle: MessageInvocation): Unit = guard.withGuard {
    if (isShutdown)
      Actor.log.warning("Actor [%s] is shut down,\n\tignoring message [%s]", toString, messageHandle)
    else {
      currentMessage = Option(messageHandle)
      try {
        dispatch(messageHandle)
      } catch {
        case e =>
          Actor.log.error(e, "Could not invoke actor [%s]", this)
          throw e
      } finally {
        currentMessage = None //TODO: Don't reset this, we might want to resend the message
      }
    }
  }

  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable): Unit = {
    if (trapExit.exists(_.isAssignableFrom(reason.getClass))) {
      faultHandler match {
        case Some(AllForOneStrategy(maxNrOfRetries, withinTimeRange)) =>
          restartLinkedActors(reason, maxNrOfRetries, withinTimeRange)

        case Some(OneForOneStrategy(maxNrOfRetries, withinTimeRange)) =>
          dead.restart(reason, maxNrOfRetries, withinTimeRange)

        case None => throw new IllegalActorStateException(
          "No 'faultHandler' defined for an actor with the 'trapExit' member field defined " +
          "\n\tto non-empty list of exception classes - can't proceed " + toString)
      }
    } else {
      notifySupervisorWithMessage(Exit(this, reason)) // if 'trapExit' is not defined then pass the Exit on
    }
  }

  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Int, withinTimeRange: Int): Unit = {
    if (maxNrOfRetriesCount == 0) restartsWithinTimeRangeTimestamp = System.currentTimeMillis // first time around
    maxNrOfRetriesCount += 1

    val tooManyRestarts = maxNrOfRetriesCount > maxNrOfRetries
    val restartingHasExpired = (System.currentTimeMillis - restartsWithinTimeRangeTimestamp) > withinTimeRange
    if (tooManyRestarts || restartingHasExpired) {
      val notification = MaximumNumberOfRestartsWithinTimeRangeReached(this, maxNrOfRetries, withinTimeRange, reason)
      Actor.log.warning(
        "Maximum number of restarts [%s] within time range [%s] reached." +
        "\n\tWill *not* restart actor [%s] anymore." +
        "\n\tLast exception causing restart was" +
        "\n\t[%s].",
        maxNrOfRetries, withinTimeRange, this, reason)
      _supervisor.foreach { sup =>
        // can supervisor handle the notification?
        if (sup.isDefinedAt(notification)) notifySupervisorWithMessage(notification)
        else Actor.log.warning(
          "No message handler defined for system message [MaximumNumberOfRestartsWithinTimeRangeReached]" +
          "\n\tCan't send the message to the supervisor [%s].", sup)
      }
      stop
    } else {
      _isBeingRestarted = true
      val failedActor = actorInstance.get
      guard.withGuard {
        lifeCycle match {
          case Some(LifeCycle(Temporary, _, _)) => shutDownTemporaryActor(this)
          case _ =>
            // either permanent or none where default is permanent
            Actor.log.info("Restarting actor [%s] configured as PERMANENT.", id)
            Actor.log.debug("Restarting linked actors for actor [%s].", id)
            restartLinkedActors(reason, maxNrOfRetries, withinTimeRange)
            Actor.log.debug("Invoking 'preRestart' for failed actor instance [%s].", id)
            if (isTypedActorDispatcher(failedActor)) restartTypedActorDispatcher(failedActor, reason)
            else                                     restartActor(failedActor, reason)
            _isBeingRestarted = false
        }
      }
    }
  }

  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Int, withinTimeRange: Int) = {
    linkedActorsAsList.foreach { actorRef =>
      actorRef.lifeCycle match {
        // either permanent or none where default is permanent
        case Some(LifeCycle(Temporary, _, _)) => shutDownTemporaryActor(actorRef)
        case _ => actorRef.restart(reason, maxNrOfRetries, withinTimeRange)
      }
    }
  }

  protected[akka] def registerSupervisorAsRemoteActor: Option[String] = guard.withGuard {
    if (_supervisor.isDefined) {
      RemoteClient.clientFor(remoteAddress.get).registerSupervisorForActor(this)
      Some(_supervisor.get.uuid)
    } else None
  }

  protected[akka] def linkedActors: JMap[String, ActorRef] = guard.withGuard {
    if (_linkedActors.isEmpty) {
      val actors = new ConcurrentHashMap[String, ActorRef]
      _linkedActors = Some(actors)
      actors
    } else _linkedActors.get
  }

  protected[akka] def linkedActorsAsList: List[ActorRef] =
    linkedActors.values.toArray.toList.asInstanceOf[List[ActorRef]]

  // ========= PRIVATE FUNCTIONS =========

  private def isTypedActorDispatcher(a: Actor): Boolean = a.isInstanceOf[Dispatcher]

  private def restartTypedActorDispatcher(failedActor: Actor, reason: Throwable) = {
    failedActor.preRestart(reason)
    failedActor.postRestart(reason)
  }

  private def restartActor(failedActor: Actor, reason: Throwable) = {
    failedActor.preRestart(reason)
    nullOutActorRefReferencesFor(failedActor)
    val freshActor = newActor
    freshActor.init
    freshActor.initTransactionalState
    actorInstance.set(freshActor)
    Actor.log.debug("Invoking 'postRestart' for new actor instance [%s].", id)
    freshActor.postRestart(reason)
  }

  private def spawnButDoNotStart[T <: Actor: Manifest]: ActorRef = guard.withGuard {
    val actorRef = Actor.actorOf(manifest[T].erasure.asInstanceOf[Class[T]].newInstance)
    if (!dispatcher.isInstanceOf[ThreadBasedDispatcher]) actorRef.dispatcher = dispatcher
    actorRef
  }

  private[this] def newActor: Actor = {
    isInInitialization = true
    Actor.actorRefInCreation.value = Some(this)
    val actor = actorFactory match {
      case Left(Some(clazz)) =>
        try {
          clazz.newInstance
        } catch {
          case e: InstantiationException => throw new ActorInitializationException(
            "Could not instantiate Actor due to:\n" + e +
            "\nMake sure Actor is NOT defined inside a class/trait," +
            "\nif so put it outside the class/trait, f.e. in a companion object," +
            "\nOR try to change: 'actorOf[MyActor]' to 'actorOf(new MyActor)'.")
        }
      case Right(Some(factory)) =>
        factory()
      case _ =>
        throw new ActorInitializationException(
          "Can't create Actor, no Actor class or factory function in scope")
    }
    if (actor eq null) throw new ActorInitializationException(
      "Actor instance passed to ActorRef can not be 'null'")
    isInInitialization = false
    actor
  }

  private def joinTransaction(message: Any) = if (isTransactionSetInScope) {
    import org.multiverse.api.ThreadLocalTransaction
    val oldTxSet = getTransactionSetInScope
    val currentTxSet = if (oldTxSet.isAborted || oldTxSet.isCommitted) {
      clearTransactionSet
      createNewTransactionSet
    } else oldTxSet
    Actor.log.ifTrace("Joining transaction set [" + currentTxSet +
                      "];\n\tactor " + toString +
                      "\n\twith message [" + message + "]")
    val mtx = ThreadLocalTransaction.getThreadLocalTransaction
    if ((mtx eq null) || mtx.getStatus.isDead) currentTxSet.incParties
    else currentTxSet.incParties(mtx, 1)
  }

  private def dispatch[T](messageHandle: MessageInvocation) = {
    Actor.log.ifTrace("Invoking actor with message:\n" + messageHandle)
    val message = messageHandle.message //serializeMessage(messageHandle.message)
    var topLevelTransaction = false
    val txSet: Option[CountDownCommitBarrier] =
      if (messageHandle.transactionSet.isDefined) messageHandle.transactionSet
      else {
        topLevelTransaction = true // FIXME create a new internal atomic block that can wait for X seconds if top level tx
        if (isTransactor) {
          Actor.log.ifTrace("Creating a new transaction set (top-level transaction)\n\tfor actor " + toString +
                            "\n\twith message " + messageHandle)
          Some(createNewTransactionSet)
        } else None
      }
    setTransactionSet(txSet)

    try {
      cancelReceiveTimeout // FIXME: leave this here?
      if (isTransactor) {
        val txFactory = _transactionFactory.getOrElse(DefaultGlobalTransactionFactory)
        atomic(txFactory) {
          actor.base(message)
          setTransactionSet(txSet) // restore transaction set to allow atomic block to do commit
        }
      } else {
        actor.base(message)
        setTransactionSet(txSet) // restore transaction set to allow atomic block to do commit
      }
    } catch {
      case e: DeadTransactionException =>
        handleExceptionInDispatch(
          new TransactionSetAbortedException("Transaction set has been aborted by another participant"),
          message, topLevelTransaction)
      case e: InterruptedException => {} // received message while actor is shutting down, ignore
      case e => handleExceptionInDispatch(e, message, topLevelTransaction)
    } finally {
      clearTransaction
      if (topLevelTransaction) clearTransactionSet
    }
  }

  private def shutDownTemporaryActor(temporaryActor: ActorRef) = {
    Actor.log.info("Actor [%s] configured as TEMPORARY and will not be restarted.", temporaryActor.id)
    temporaryActor.stop
    linkedActors.remove(temporaryActor.uuid) // remove the temporary actor
    // if last temporary actor is gone, then unlink me from supervisor
    if (linkedActors.isEmpty) {
      Actor.log.info(
        "All linked actors have died permanently (they were all configured as TEMPORARY)" +
        "\n\tshutting down and unlinking supervisor actor as well [%s].",
        temporaryActor.id)
        notifySupervisorWithMessage(UnlinkAndStop(this))
    }
  }

  private def handleExceptionInDispatch(reason: Throwable, message: Any, topLevelTransaction: Boolean) = {
    Actor.log.error(reason, "Exception when invoking \n\tactor [%s] \n\twith message [%s]", this, message)

    _isBeingRestarted = true
    // abort transaction set
    if (isTransactionSetInScope) {
      val txSet = getTransactionSetInScope
      if (!txSet.isCommitted) {
        Actor.log.debug("Aborting transaction set [%s]", txSet)
        txSet.abort
      }
    }

    senderFuture.foreach(_.completeWithException(this, reason))

    clearTransaction
    if (topLevelTransaction) clearTransactionSet

    notifySupervisorWithMessage(Exit(this, reason))
  }

  private def notifySupervisorWithMessage(notification: LifeCycleMessage) = {
    // FIXME to fix supervisor restart of remote actor for oneway calls, inject a supervisor proxy that can send notification back to client
    _supervisor.foreach { sup =>
      if (sup.isShutdown) { // if supervisor is shut down, game over for all linked actors
//        shutdownLinkedActors
//        stop
      } else sup ! notification // else notify supervisor
    }
  }

  private def nullOutActorRefReferencesFor(actor: Actor) = {
    actorSelfFields._1.set(actor, null)
    actorSelfFields._2.set(actor, null)
    actorSelfFields._3.set(actor, null)
  }

  private def findActorSelfField(clazz: Class[_]): Tuple3[Field, Field, Field] = {
    try {
      val selfField =       clazz.getDeclaredField("self")
      val optionSelfField = clazz.getDeclaredField("optionSelf")
      val someSelfField =   clazz.getDeclaredField("someSelf")
      selfField.setAccessible(true)
      optionSelfField.setAccessible(true)
      someSelfField.setAccessible(true)
      (selfField, optionSelfField, someSelfField)
    } catch {
      case e: NoSuchFieldException =>
        val parent = clazz.getSuperclass
        if (parent != null) findActorSelfField(parent)
        else throw new IllegalActorStateException(
          toString + " is not an Actor since it have not mixed in the 'Actor' trait")
    }
  }

  private def initializeActorInstance = {
    actor.init // run actor init and initTransactionalState callbacks
    actor.initTransactionalState
    Actor.log.debug("[%s] has started", toString)
    ActorRegistry.register(this)
    if (id == "N/A") id = actorClass.getName // if no name set, then use default name (class name)
    clearTransactionSet // clear transaction set that might have been created if atomic block has been used within the Actor constructor body
    checkReceiveTimeout
  }

  private def serializeMessage(message: AnyRef): AnyRef = if (Actor.SERIALIZE_MESSAGES) {
    if (!message.isInstanceOf[String] &&
        !message.isInstanceOf[Byte] &&
        !message.isInstanceOf[Int] &&
        !message.isInstanceOf[Long] &&
        !message.isInstanceOf[Float] &&
        !message.isInstanceOf[Double] &&
        !message.isInstanceOf[Boolean] &&
        !message.isInstanceOf[Char] &&
        !message.isInstanceOf[Tuple2[_, _]] &&
        !message.isInstanceOf[Tuple3[_, _, _]] &&
        !message.isInstanceOf[Tuple4[_, _, _, _]] &&
        !message.isInstanceOf[Tuple5[_, _, _, _, _]] &&
        !message.isInstanceOf[Tuple6[_, _, _, _, _, _]] &&
        !message.isInstanceOf[Tuple7[_, _, _, _, _, _, _]] &&
        !message.isInstanceOf[Tuple8[_, _, _, _, _, _, _, _]] &&
        !message.getClass.isArray &&
        !message.isInstanceOf[List[_]] &&
        !message.isInstanceOf[scala.collection.immutable.Map[_, _]] &&
        !message.isInstanceOf[scala.collection.immutable.Set[_]]) {
      Serializer.Java.deepClone(message)
    } else message
  } else message
}

/**
 * Remote ActorRef that is used when referencing the Actor on a different node than its "home" node.
 * This reference is network-aware (remembers its origin) and immutable.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] case class RemoteActorRef private[akka] (
//  uuid: String, className: String, hostname: String, port: Int, timeOut: Long, isOnRemoteHost: Boolean) extends ActorRef {
  uuuid: String, val className: String, val hostname: String, val port: Int, _timeout: Long, loader: Option[ClassLoader])
  extends ActorRef {
  _uuid = uuuid
  timeout = _timeout

  start
  lazy val remoteClient = RemoteClient.clientFor(hostname, port, loader)

  def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit = {
    remoteClient.send[Any](createRemoteRequestProtocolBuilder(this, message, true, senderOption).build, None)
  }

  def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
      message: Any,
      timeout: Long,
      senderOption: Option[ActorRef],
      senderFuture: Option[CompletableFuture[T]]): CompletableFuture[T] = {
    val future = remoteClient.send(createRemoteRequestProtocolBuilder(this, message, false, senderOption).build, senderFuture)
    if (future.isDefined) future.get
    else throw new IllegalActorStateException("Expected a future from remote call to actor " + toString)
  }

  def start: ActorRef = {
    _isRunning = true
    this
  }

  def stop: Unit = {
    _isRunning = false
    _isShutDown = true
  }

  /**
   * Returns the class name for the Actor instance that is managed by the ActorRef.
   */
  def actorClassName: String = className

  protected[akka] def registerSupervisorAsRemoteActor: Option[String] = None

  // ==== NOT SUPPORTED ====
  def actorClass: Class[_ <: Actor] = unsupported
  def dispatcher_=(md: MessageDispatcher): Unit = unsupported
  def dispatcher: MessageDispatcher = unsupported
  def makeTransactionRequired: Unit = unsupported
  def transactionConfig_=(config: TransactionConfig): Unit = unsupported
  def transactionConfig: TransactionConfig = unsupported
  def makeRemote(hostname: String, port: Int): Unit = unsupported
  def makeRemote(address: InetSocketAddress): Unit = unsupported
  def homeAddress_=(address: InetSocketAddress): Unit = unsupported
  def remoteAddress: Option[InetSocketAddress] = unsupported
  def link(actorRef: ActorRef): Unit = unsupported
  def unlink(actorRef: ActorRef): Unit = unsupported
  def startLink(actorRef: ActorRef): Unit = unsupported
  def startLinkRemote(actorRef: ActorRef, hostname: String, port: Int): Unit = unsupported
  def spawn[T <: Actor : Manifest]: ActorRef = unsupported
  def spawnRemote[T <: Actor: Manifest](hostname: String, port: Int): ActorRef = unsupported
  def spawnLink[T <: Actor: Manifest]: ActorRef = unsupported
  def spawnLinkRemote[T <: Actor : Manifest](hostname: String, port: Int): ActorRef = unsupported
  def supervisor: Option[ActorRef] = unsupported
  def shutdownLinkedActors: Unit = unsupported
  protected[akka] def mailbox: AnyRef = unsupported
  protected[akka] def mailbox_=(value: AnyRef):AnyRef = unsupported
  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable): Unit = unsupported
  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Int, withinTimeRange: Int): Unit = unsupported
  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Int, withinTimeRange: Int): Unit = unsupported
  protected[akka] def linkedActors: JMap[String, ActorRef] = unsupported
  protected[akka] def linkedActorsAsList: List[ActorRef] = unsupported
  protected[akka] def invoke(messageHandle: MessageInvocation): Unit = unsupported
  protected[akka] def remoteAddress_=(addr: Option[InetSocketAddress]): Unit = unsupported
  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit = unsupported
  protected[this] def actorInstance: AtomicReference[Actor] = unsupported

  private def unsupported = throw new UnsupportedOperationException("Not supported for RemoteActorRef")
}

/**
 * Copyright (C) 2010, Progress Software Corporation and/or its
 * subsidiaries or affiliates.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.scalablesolutions.akka.actor.dispatch

import collection.mutable.ListBuffer
import java.util.concurrent.TimeUnit
import java.net.InetSocketAddress
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.{SocketChannel, SelectionKey, ServerSocketChannel}

import se.scalablesolutions.akka.actor._
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.dispatch.HawtDispatcher
import org.fusesource.hawtdispatch.DispatchSource
import org.fusesource.hawtdispatch.ScalaDispatch._

/**
 * This is an example of how to crate an Akka actor based TCP echo server using
 * the HawtDispatch dispatcher and NIO event sources.
 *
 */
object HawtDispatcherEchoServer {

  private val hawt = new HawtDispatcher
  var port=4444;
  var useReactorPattern=true

  def main(args:Array[String]):Unit =  run

  def run() = {
    val server = actorOf(new Server(port))
    server.start
    Scheduler.schedule(server, DisplayStats, 1, 5, TimeUnit.SECONDS)

    println("Press enter to shutdown.");
    System.in.read
    server ! Shutdown
  }

  case object Shutdown
  case object DisplayStats
  case class SessionClosed(session:ActorRef)

  class Server(val port: Int) extends Actor {

    self.dispatcher = hawt

    var channel:ServerSocketChannel = _
    var accept_source:DispatchSource = _
    var sessions = ListBuffer[ActorRef]()

    override def init = {
      channel = ServerSocketChannel.open();
      channel.socket().bind(new InetSocketAddress(port));
      channel.configureBlocking(false);

      // Setup the accept source, it will callback to the handler methods
      // via the actor's mailbox so you don't need to worry about
      // synchronizing with the local variables
      accept_source = createSource(channel, SelectionKey.OP_ACCEPT, HawtDispatcher.queue(self));
      accept_source.setEventHandler(^{ accept  });
      accept_source.setDisposer(^{
        channel.close();
        println("Closed port: "+port);
      });

      accept_source.resume

      println("Listening on port: "+port);
    }


    private def accept() = {
      var socket = channel.accept();
      while( socket!=null ) {
        try {
          socket.configureBlocking(false);
          val session = actorOf(new Session(self, socket))
          session.start()
          sessions += session
        } catch {
          case e: Exception =>
            socket.close
        }
        socket = channel.accept();
      }
    }

    def receive = {
      case SessionClosed(session) =>
        sessions = sessions.filterNot( _ == session )
        session.stop
      case DisplayStats =>
        sessions.foreach { session=>
          session ! DisplayStats
        }
      case Shutdown =>
        sessions.foreach { session=>
          session.stop
        }
        sessions.clear
        accept_source.release
        self.stop
    }
  }

  class Session(val server:ActorRef, val channel: SocketChannel) extends Actor {

    self.dispatcher = hawt

    val buffer = ByteBuffer.allocate(1024);
    val remote_address = channel.socket.getRemoteSocketAddress.toString

    var read_source:DispatchSource = _
    var write_source:DispatchSource = _

    var readCounter = 0L
    var writeCounter = 0L
    var closed = false

    override def init = {

      if(useReactorPattern) {
        // Then we will be using the reactor pattern for handling IO:
        // Pin this actor to a single thread.  The read/write event sources will poll
        // a Selector on the pinned thread.  Since the IO events are generated on the same
        // thread as where the Actor is pinned to, it can avoid a substantial amount
        // thread synchronization.  Plus your GC will perform better since all the IO
        // processing is done on a single thread.
        HawtDispatcher.pin(self)
      } else {
        // Then we will be using sing the proactor pattern for handling IO:
        // Then the actor will not be pinned to a specific thread.  The read/write
        // event sources will poll a Selector and then asynchronously dispatch the
        // event's to the actor via the thread pool.
      }

      // Setup the sources, they will callback to the handler methods
      // via the actor's mailbox so you don't need to worry about
      // synchronizing with the local variables
      read_source = createSource(channel, SelectionKey.OP_READ, HawtDispatcher.queue(self));
      read_source.setEventHandler(^{ read })
      read_source.setCancelHandler(^{ close })

      write_source = createSource(channel, SelectionKey.OP_READ, HawtDispatcher.queue(self));
      write_source.setEventHandler(^{ write   })
      write_source.setCancelHandler(^{ close })

      read_source.resume
      println("Accepted connection from: "+remote_address);
    }

    override def shutdown = {
      closed = true
      read_source.release
      write_source.release
      channel.close
    }

    private def catchio(func: =>Unit):Unit = {
      try {
        func
      } catch {
        case e:IOException => close
      }
    }

    def read():Unit = catchio {
      channel.read(buffer) match {
        case -1 =>
          close // peer disconnected.
        case 0 =>
        case count:Int =>
          readCounter += count
          buffer.flip;
          read_source.suspend
          write_source.resume
          write()
      }
    }

    def write() = catchio {
      writeCounter += channel.write(buffer)
      if (buffer.remaining == 0) {
        buffer.clear
        write_source.suspend
        read_source.resume
      }
    }


    def close() = {
      if( !closed ) {
        closed = true
        server ! SessionClosed(self)
      }
    }

    def receive = {
      case DisplayStats =>
        println("connection to %s reads: %,d bytes, writes: %,d".format(remote_address, readCounter, writeCounter))
    }

  }

}

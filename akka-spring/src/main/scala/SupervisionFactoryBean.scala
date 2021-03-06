/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.springframework.beans.factory.config.AbstractFactoryBean
import se.scalablesolutions.akka.config.TypedActorConfigurator
import se.scalablesolutions.akka.config.JavaConfig._
import AkkaSpringConfigurationTags._
import reflect.BeanProperty


/**
 * Factory bean for supervisor configuration.
 * @author michaelkober
 */
class SupervisionFactoryBean extends AbstractFactoryBean[TypedActorConfigurator] {
  @BeanProperty var restartStrategy: RestartStrategy = _
  @BeanProperty var supervised: List[TypedActorProperties] = _

  /*
   * @see org.springframework.beans.factory.FactoryBean#getObjectType()
   */
  def getObjectType: Class[TypedActorConfigurator] = classOf[TypedActorConfigurator]

  /*
   * @see org.springframework.beans.factory.config.AbstractFactoryBean#createInstance()
   */
  def createInstance: TypedActorConfigurator =  {
    val configurator = new TypedActorConfigurator()

    configurator.configure(
      restartStrategy,
      supervised.map(createComponent(_)).toArray
      ).supervise
  }

  /**
   * Create configuration for TypedActor
   */
  private[akka] def createComponent(props: TypedActorProperties): Component = {
    import StringReflect._
    val lifeCycle = if (!props.lifecycle.isEmpty && props.lifecycle.equalsIgnoreCase(VAL_LIFECYCYLE_TEMPORARY)) new LifeCycle(new Temporary()) else new LifeCycle(new Permanent())
    val isRemote = (props.host != null) && (!props.host.isEmpty)
    val withInterface = (props.interface != null) && (!props.interface.isEmpty)
    if (isRemote) {
      val remote = new RemoteAddress(props.host, props.port)
      if (withInterface) {
        new Component(props.interface.toClass, props.target.toClass, lifeCycle, props.timeout, props.transactional, remote)
      } else {
        new Component(props.target.toClass, lifeCycle, props.timeout, props.transactional, remote)
      }
    } else {
      if (withInterface) {
        new Component(props.interface.toClass, props.target.toClass, lifeCycle, props.timeout, props.transactional)
      } else {
        new Component(props.target.toClass, lifeCycle, props.timeout, props.transactional)
      }
    }
  }
}

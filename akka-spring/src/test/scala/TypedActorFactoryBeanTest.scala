/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.springframework.core.io.ResourceEditor
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * Test for TypedActorFactoryBean
 * @author michaelkober
 */
@RunWith(classOf[JUnitRunner])
class TypedActorFactoryBeanTest extends Spec with ShouldMatchers {

  describe("A TypedActorFactoryBean") {
    val bean = new TypedActorFactoryBean
    it("should have java getters and setters for all properties") {
      bean.setTarget("java.lang.String")
      assert(bean.getTarget == "java.lang.String")
      bean.setTimeout(1000)
      assert(bean.getTimeout == 1000)
    }

    it("should create a remote typed actor when a host is set") {
      bean.setHost("some.host.com");
      assert(bean.isRemote)
    }

    it("should create object that implements the given interface") {
      bean.setInterface("com.biz.IPojo");
      assert(bean.hasInterface)
    }

    it("should create an typed actor with dispatcher if dispatcher is set") {
      val props = new DispatcherProperties()
      props.dispatcherType = "executor-based-event-driven"
      bean.setDispatcher(props);
      assert(bean.hasDispatcher)
    }

    it("should return the object type") {
      bean.setTarget("java.lang.String")
      assert(bean.getObjectType == classOf[String])
    }

/*
    it("should create an application context and verify dependency injection") {
      var ctx = new ClassPathXmlApplicationContext("appContext.xml");
      val target:ResourceEditor = ctx.getBean("bean").asInstanceOf[ResourceEditor]
      assert(target.getSource === "someString")

      val pojoInf = ctx.getBean("pojoInf").asInstanceOf[PojoInf];
      Thread.sleep(200)
      assert(pojoInf.isPostConstructInvoked)
      assert(pojoInf.getString == "akka rocks")
      assert(pojoInf.gotApplicationContext)
    }

    it("should stop the created typed actor when scope is singleton and the context is closed") {
      var ctx = new ClassPathXmlApplicationContext("appContext.xml");
      val target = ctx.getBean("bean-singleton").asInstanceOf[SampleBean]
      assert(!target.down)
      ctx.close
      assert(target.down)
    }

    it("should not stop the created typed actor when scope is prototype and the context is closed") {
      var ctx = new ClassPathXmlApplicationContext("appContext.xml");
      val target = ctx.getBean("bean-prototype").asInstanceOf[SampleBean]
      assert(!target.down)
      ctx.close
      assert(!target.down)
    }
    */
  }
}

/*
   // ------ NOTE: Can't work now when we only support POJO with interface -----

    it("should create a proxy of type ResourceEditor") {
      val bean = new TypedActorFactoryBean()
      // we must have a java class here
      bean.setTarget("org.springframework.core.io.ResourceEditor")
      val entries = new PropertyEntries()
      val entry = new PropertyEntry()
      entry.name = "source"
      entry.value = "sourceBeanIsAString"
      entries.add(entry)
      bean.setProperty(entries)
      assert(bean.getObjectType == classOf[ResourceEditor])

      // Check that we have injected the depencency correctly
      val target:ResourceEditor = bean.createInstance.asInstanceOf[ResourceEditor]
      assert(target.getSource === entry.value)
    }
*/

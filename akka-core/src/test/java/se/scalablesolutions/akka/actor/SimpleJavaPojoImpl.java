package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.actor.*;
import se.scalablesolutions.akka.dispatch.CompletableFuture;

public class SimpleJavaPojoImpl extends TypedActor implements SimpleJavaPojo {

  public static boolean _pre = false;
  public static boolean _post = false;
  public static boolean _down = false;
  public static void reset() {
    _pre = false;
    _post = false;
    _down = false;
  }

  private String name;

  public Object getSender() {
    return getContext().getSender();
  }

  public CompletableFuture<Object> getSenderFuture() {
    return getContext().getSenderFuture();
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  @Override
  public void preRestart(Throwable e) {
    _pre = true;
  }

  @Override
  public void postRestart(Throwable e) {
    _post = true;
  }

  public void throwException() {
    throw new RuntimeException();
  }
}

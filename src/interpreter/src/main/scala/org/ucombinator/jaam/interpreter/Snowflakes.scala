package org.ucombinator.jaam.interpreter

import scala.collection.JavaConversions._
import scala.collection.mutable

import com.esotericsoftware.minlog.Log

import soot.{Main => SootMain, Unit => SootUnit, Value => SootValue, _}
import soot.jimple.{Stmt => SootStmt, _}

// Snowflakes are special Java procedures whose behavior we know and special-case.
// For example, native methods (that would be difficult to analyze) are snowflakes.

// TODO: make SnowflakeHandler record its method description
// TODO: this and params method
// TODO: returns method

case class SnowflakeBasePointer(val name : String) extends BasePointer

// Uniquely identifies a particular method somewhere in the program.
case class MethodDescription(val className : String,
                             val methodName : String,
                             val parameterTypes : List[String],
                             val returnType : String)

// Snowflakes are special-cased methods
abstract class SnowflakeHandler {
  // self is None if this is a static call and Some otherwise
  def apply(state : State, nextStmt : Stmt, self : Option[Value], args : List[D]) : Set[AbstractState]
}

abstract class StaticSnowflakeHandler extends SnowflakeHandler {
  def apply(state : State, nextStmt : Stmt, args : List[D]) : Set[AbstractState]

  override def apply(state : State, nextStmt : Stmt, self : Option[Value], args : List[D]) : Set[AbstractState] =
    self match {
      case None => this.apply(state, nextStmt, args)
      case Some(_) => throw new Exception("Static Snowflake used on non-static call. snowflake = "+this+" state = "+state)
    }
}

abstract class NonstaticSnowflakeHandler extends SnowflakeHandler {
  def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState]

  override def apply(state : State, nextStmt : Stmt, self : Option[Value], args : List[D]) : Set[AbstractState] =
    self match {
      case None => throw new Exception("Non-static Snowflake used on static call. snowflake = "+this+" state = "+state)
      case Some(s) => this.apply(state, nextStmt, s, args)
    }
}

object NoOpSnowflake extends SnowflakeHandler {
  override def apply(state : State, nextStmt : Stmt, self : Option[Value], args : List[D]) : Set[AbstractState] =
    Set(state.copyState(stmt = nextStmt))
}

// TODO/soundness: Add JohnSnowflake for black-holes. Not everything becomes top, but an awful lot will.

case class ReturnSnowflake(value : D) extends SnowflakeHandler {
  override def apply(state : State, nextStmt : Stmt, self : Option[Value], args : List[D]) : Set[AbstractState] = {
    state.stmt.sootStmt match {
      case sootStmt : DefinitionStmt => state.store.update(state.addrsOf(sootStmt.getLeftOp()), value)
      case sootStmt : InvokeStmt => //state.store
    }
    Set(state.copyState(stmt = nextStmt))
  }
}

case class UninitializedSnowflakeObjectException(className : String) extends RuntimeException

case class ReturnObjectSnowflake(name : String) extends SnowflakeHandler {
  override def apply(state : State, nextStmt : Stmt, self : Option[Value], args : List[D]) : Set[AbstractState] = {
    state.stmt.sootStmt match {
      case stmt : DefinitionStmt => state.store.update(state.addrsOf(stmt.getLeftOp),
        D(Set(ObjectValue(Soot.getSootClass(name), SnowflakeBasePointer(name)))))
      case stmt : InvokeStmt => //state.store
    }
    state.store.join(Snowflakes.createObject(name, List()))
    Set[AbstractState](state.copyState(stmt = nextStmt))
  }
}

case class ReturnArraySnowflake(baseType: String, dim: Int) extends NonstaticSnowflakeHandler {
  override def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState] = {
    val sizes = List.fill(dim)(D.atomicTop)
    val sootBaseType = Soot.getSootType(baseType)
    val at = soot.ArrayType.v(sootBaseType, dim)
    val newNewStore = state.stmt.sootStmt match {
      case stmt : DefinitionStmt =>
        Snowflakes.createArray(at, sizes, state.addrsOf(stmt.getLeftOp), state.malloc())
      case stmt : InvokeStmt => Store(mutable.Map[Addr, D]())
    }
    state.store.join(newNewStore)
    Set(state.copyState(stmt=nextStmt))
  }
}

case class PutStaticSnowflake(clas : String, field : String) extends StaticSnowflakeHandler {
  override def apply(state : State, nextStmt : Stmt, args : List[D]) : Set[AbstractState] = {
    val sootField = Jimple.v.newStaticFieldRef(Soot.getSootClass(clas).getFieldByName(field).makeRef())
    val value = args(0)
    state.store.update(state.addrsOf(sootField), value)
    Set(state.copyState(stmt = nextStmt))
  }
}

object HashMapSnowflakes {
  lazy val HashMap = Soot.getSootClass("java.util.HashMap")
  lazy val EntrySet = Soot.getSootClass("java.util.Set")
  lazy val Iterator = Soot.getSootClass("java.util.Iterator")
  lazy val Entry = Soot.getSootClass("java.util.Map$" +"Entry") // Spilt up "$Entry to keep linter happy

  Snowflakes.table.put(MethodDescription("java.util.HashMap", SootMethod.constructorName, List(), "void"), HashMapSnowflakes.init())
  Snowflakes.table.put(MethodDescription("java.util.HashMap", "put", List("java.lang.Object", "java.lang.Object"), "java.lang.Object"), HashMapSnowflakes.put())
  Snowflakes.table.put(MethodDescription("java.util.HashMap", "get", List("java.lang.Object"), "java.lang.Object"), HashMapSnowflakes.get())
  Snowflakes.table.put(MethodDescription("java.util.Iterator", "hasNext", List(), "boolean"), hasNext())
  Snowflakes.table.put(MethodDescription("java.util.Iterator", "next", List(), "java.lang.Object"), next())

  case class KeysAddr(val bp : BasePointer) extends Addr
  case class ValuesAddr(val bp : BasePointer) extends Addr

  case class init() extends NonstaticSnowflakeHandler {
    override def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState] = {
      // void HashMap.<init>():
      //  this.keys = {}
      //  this.values = {}

      var extraStates = Set[AbstractState]()
      self match {
        case ObjectValue(sootClass, bp) if Soot.isSubclass(sootClass, HashMap) =>
          state.store.update(KeysAddr(bp), D(Set()))
          state.store.update(ValuesAddr(bp), D(Set()))
        case _ =>
          Snowflakes.warn(state.id, null, null)
          extraStates ++= NoOpSnowflake(state, nextStmt, Some(self), args)
      }

      extraStates + state.copyState(stmt = nextStmt)
    }
  }

  case class put() extends NonstaticSnowflakeHandler {
    override def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState] = {
      // Object HashMap.put(Object o1, Object o2):
      //   this.keys += o1
      //   this.values += o2

      // TODO: avoid duplication with get()
      var extraStates = Set[AbstractState]()
      state.stmt.sootStmt match {
        case stmt : InvokeStmt => {}
        case stmt : DefinitionStmt =>
          self match {
            case ObjectValue(sootClass, bp) if Soot.isSubclass(sootClass, HashMap) =>
              // D.atomicTop is for the null returned when the key had no previous assignment
              state.store.update(state.addrsOf(stmt.getLeftOp),
                state.store(Set[Addr](ValuesAddr(bp))).join(D.atomicTop))
            case _ =>
              Snowflakes.warn(state.id, null, null)
              extraStates ++= ReturnObjectSnowflake("java.lang.Object")(state, nextStmt, Some(self), args)
          }
      }

      val key = args(0)
      val value = args(1)
      self match {
        case ObjectValue(sootClass, bp) if Soot.isSubclass(sootClass, HashMap) =>
          state.store.update(KeysAddr(bp), key)
          state.store.update(ValuesAddr(bp), value)
        case _ => {} // Already taken care of in the first half of this function
      }

      extraStates + state.copyState(stmt = nextStmt)
    }
  }

  case class get() extends NonstaticSnowflakeHandler {
    override def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState] = {
      // Object HashMap.get(Object):
      //   return this.values

      var extraStates = Set[AbstractState]()
      state.stmt.sootStmt match {
        case stmt : InvokeStmt => {}
        case stmt : DefinitionStmt =>
          self match {
            case ObjectValue(sootClass, bp) if Soot.isSubclass(sootClass, HashMap) =>
              // D.atomicTop is for the null returned when the key is not found
              state.store.update(state.addrsOf(stmt.getLeftOp),
                  state.store(Set[Addr](ValuesAddr(bp))).join(D.atomicTop))
            case _ =>
              Snowflakes.warn(state.id, null, null)
              extraStates ++= ReturnObjectSnowflake("java.lang.Object")(state, nextStmt, Some(self), args)
          }
      }

      extraStates + state.copyState(stmt = nextStmt)
    }
  }

  // java.util.Set HashMap.entrySet():
  //   return this
  case class EntrySetOfHashMap(val bp : BasePointer) extends BasePointer
  case class entrySet() extends NonstaticSnowflakeHandler {
    override def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState] = {
      var extraStates = Set[AbstractState]()
      state.stmt.sootStmt match {
        case stmt : InvokeStmt => {}
        case stmt : DefinitionStmt =>
          self match {
            case ObjectValue(sootClass, bp) if Soot.isSubclass(sootClass, HashMap) =>
              // D.atomicTop is for the null returned when the key is not found
              state.store.update(state.addrsOf(stmt.getLeftOp),
                D(Set(ObjectValue(EntrySet, EntrySetOfHashMap(bp)))))
            case _ =>
              Snowflakes.warn(state.id, null, null)
              extraStates ++= ReturnObjectSnowflake("java.util.Set")(state, nextStmt, Some(self), args)
          }
      }

      extraStates + state.copyState(stmt = nextStmt)
    }
  }

  // java.util.Iterator EntrySet.iterator():
  //   return this
  case class IteratorOfEntrySetOfHashMap(val bp : BasePointer) extends BasePointer
  case class iterator() extends NonstaticSnowflakeHandler {
    override def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState] = {
      var extraStates = Set[AbstractState]()
      state.stmt.sootStmt match {
        case stmt : InvokeStmt => {}
        case stmt : DefinitionStmt =>
          self match {
            case ObjectValue(sootClass, EntrySetOfHashMap(bp)) if Soot.isSubclass(sootClass, EntrySet) =>
              // D.atomicTop is for the null returned when the key is not found
              state.store.update(state.addrsOf(stmt.getLeftOp),
                D(Set(ObjectValue(Iterator, IteratorOfEntrySetOfHashMap(bp)))))
            case x =>
              Snowflakes.warn(state.id, null, null)
              extraStates ++= ReturnObjectSnowflake("java.util.Set")(state, nextStmt, Some(self), args)
          }
      }

      extraStates + state.copyState(stmt = nextStmt)
    }
  }

  // boolean Iterator.hasNext():
  //   return atomicTop
  case class hasNext() extends NonstaticSnowflakeHandler {
    override def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState] = {
      var extraStates = Set[AbstractState]()
      state.stmt.sootStmt match {
        case stmt : InvokeStmt => {}
        case stmt : DefinitionStmt =>
          self match {
            case ObjectValue(sootClass, IteratorOfEntrySetOfHashMap(bp)) if Soot.isSubclass(sootClass, Iterator) =>
              // D.atomicTop is for the null returned when the key is not found
              state.store.update(state.addrsOf(stmt.getLeftOp), D.atomicTop)
            case ObjectValue(sootClass, ArrayListSnowflakes.IteratorOfArrayList(bp)) if Soot.isSubclass(sootClass, Iterator) =>
              // D.atomicTop is for the null returned when the key is not found
              state.store.update(state.addrsOf(stmt.getLeftOp), D.atomicTop)
            case _ =>
              Snowflakes.warn(state.id, null, null)
              extraStates ++= ReturnSnowflake(D.atomicTop)(state, nextStmt, Some(self), args)
          }
      }

      extraStates + state.copyState(stmt = nextStmt)
    }
  }

  // java.lang.Object Iterator.next()
  //    return (Entry) this
  case class EntryOfIteratorOfEntrySetOfHashMap(val bp : BasePointer) extends BasePointer
  case class next() extends NonstaticSnowflakeHandler {
    override def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState] = {
      var extraStates = Set[AbstractState]()
      state.stmt.sootStmt match {
        case stmt : InvokeStmt => {}
        case stmt : DefinitionStmt =>
          self match {
            case ObjectValue(sootClass, IteratorOfEntrySetOfHashMap(bp)) if Soot.isSubclass(sootClass, Iterator) =>
              // D.atomicTop is for the null returned when the key is not found
              state.store.update(state.addrsOf(stmt.getLeftOp),
                D(Set(ObjectValue(Entry, EntryOfIteratorOfEntrySetOfHashMap(bp)))))
            case ObjectValue(sootClass, ArrayListSnowflakes.IteratorOfArrayList(bp)) if Soot.isSubclass(sootClass, Iterator) =>
              // D.atomicTop is for the null returned when the key is not found
              // TODO: throw exception
              state.store.update(state.addrsOf(stmt.getLeftOp),
                state.store(Set[Addr](ArrayListSnowflakes.RefAddr(bp))))
            case _ =>
              Snowflakes.warn(state.id, null, null)
              extraStates ++= ReturnObjectSnowflake("java.lang.Object")(state, nextStmt, Some(self), args)
          }
      }

      extraStates + state.copyState(stmt = nextStmt)
    }
  }

  // java.lang.Object Entry.getKey()
  case class getKey() extends NonstaticSnowflakeHandler {
    override def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState] = {
      var extraStates = Set[AbstractState]()
      state.stmt.sootStmt match {
        case stmt : InvokeStmt => {}
        case stmt : DefinitionStmt =>
          self match {
            case ObjectValue(sootClass, EntryOfIteratorOfEntrySetOfHashMap(bp)) if Soot.isSubclass(sootClass, Entry) =>
              // D.atomicTop is for the null returned when the key is not found
              state.store.update(state.addrsOf(stmt.getLeftOp),
                state.store(Set[Addr](KeysAddr(bp))).join(D.atomicTop))
            case _ =>
              Snowflakes.warn(state.id, null, null)
              extraStates ++= ReturnObjectSnowflake("java.lang.Object")(state, nextStmt, Some(self), args)
          }
      }

      extraStates + state.copyState(stmt = nextStmt)
    }
  }

  // java.lang.Object getValue()
  case class getValue() extends NonstaticSnowflakeHandler {
    override def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState] = {
      var extraStates = Set[AbstractState]()
      state.stmt.sootStmt match {
        case stmt : InvokeStmt => {}
        case stmt : DefinitionStmt =>
          self match {
            case ObjectValue(sootClass, EntryOfIteratorOfEntrySetOfHashMap(bp)) if Soot.isSubclass(sootClass, Entry) =>
              // D.atomicTop is for the null returned when the key is not found
              state.store.update(state.addrsOf(stmt.getLeftOp),
                state.store(Set[Addr](ValuesAddr(bp))).join(D.atomicTop))
            case _ =>
              Snowflakes.warn(state.id, null, null)
              extraStates ++= ReturnObjectSnowflake("java.lang.Object")(state, nextStmt, Some(self), args)
          }
      }

      extraStates + state.copyState(stmt = nextStmt)
    }
  }

}

object ArrayListSnowflakes {
  lazy val ArrayList = Soot.getSootClass("java.util.ArrayList")
  lazy val Iterator = Soot.getSootClass("java.util.Iterator")

  Snowflakes.table.put(MethodDescription("java.util.ArrayList", SootMethod.constructorName, List(), "void"), ArrayListSnowflakes.init())
  Snowflakes.table.put(MethodDescription("java.util.ArrayList", "add", List("java.lang.Object"), "boolean"), ArrayListSnowflakes.add())
  Snowflakes.table.put(MethodDescription("java.util.ArrayList", "iterator", List(), "java.util.Iterator"), ArrayListSnowflakes.iterator())
  //  Snowflakes.table.put(MethodDescription("java.util.HashMap", "get", List("java.lang.Object"), "java.lang.Object"), HashMapSnowflakes.get())

  case class LengthAddr(val bp : BasePointer) extends Addr
  case class RefAddr(val bp : BasePointer) extends Addr

  case class init() extends NonstaticSnowflakeHandler {
    override def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState] = {
      // void ArrayList.<init>():
      //  this.length = top
      //  this.refs = {}
      var extraStates = Set[AbstractState]()
      self match {
        case ObjectValue(sootClass, bp) if Soot.isSubclass(sootClass, ArrayList) =>
          state.store.update(LengthAddr(bp), D.atomicTop)
          state.store.update(RefAddr(bp), D.atomicTop) // D.atomicTop is so we don't get undefiend addrs exception
        case _ =>
          Snowflakes.warn(state.id, null, null)
          extraStates ++= NoOpSnowflake(state, nextStmt, Some(self), args)
      }

      extraStates + state.copyState(stmt = nextStmt)
    }
  }

  case class add() extends NonstaticSnowflakeHandler {
    override def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState] = {
      // boolean ArrayList.add(Object o)
      //   this.length += top
      //   this.refs += o
      // TODO: avoid duplication
      var extraStates = Set[AbstractState]()
      state.stmt.sootStmt match {
        case stmt : InvokeStmt => {}
        case stmt : DefinitionStmt =>
          self match {
            case ObjectValue(sootClass, bp) if Soot.isSubclass(sootClass, ArrayList) =>
              state.store.update(state.addrsOf(stmt.getLeftOp), D.atomicTop)
            case _ =>
              Snowflakes.warn(state.id, null, null)
              extraStates ++= ReturnSnowflake(D.atomicTop)(state, nextStmt, Some(self), args)
          }
      }

      val value = args(0)
      self match {
        case ObjectValue(sootClass, bp) if Soot.isSubclass(sootClass, ArrayList) =>
          state.store.update(RefAddr(bp), value)
        case _ => {} // already handled by the code in the first half of this function
      }

      extraStates + state.copyState(stmt = nextStmt)
    }
  }

  // java.util.Iterator ArrayList.iterator():
  //   return this
  case class IteratorOfArrayList(val bp : BasePointer) extends BasePointer
  case class iterator() extends NonstaticSnowflakeHandler {
    override def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState] = {
      var extraStates = Set[AbstractState]()
      state.stmt.sootStmt match {
        case stmt : InvokeStmt => {}
        case stmt : DefinitionStmt =>
          self match {
            case ObjectValue(sootClass, bp) if Soot.isSubclass(sootClass, ArrayList) =>
              // D.atomicTop is for the null returned when the key is not found
              state.store.update(state.addrsOf(stmt.getLeftOp),
                D(Set(ObjectValue(Iterator, IteratorOfArrayList(bp)))))
            case _ =>
              Snowflakes.warn(state.id, null, null)
              extraStates ++= ReturnObjectSnowflake("java.util.Iterator")(state, nextStmt, Some(self), args)
          }
      }

      extraStates + state.copyState(stmt = nextStmt)
    }
  }
}

object ClassSnowflakes {
  Snowflakes.table.put(MethodDescription("java.lang.Class", "newInstance", List(), "java.lang.Object"), newInstance())
  case class newInstance() extends NonstaticSnowflakeHandler {
    override def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState] = {
      val local = state.stmt.sootStmt match {
        case stmt : DefinitionStmt => stmt.getLeftOp().asInstanceOf[Local]
      }
      val lhsAddr = state.addrsOf(local)

      self match {
        case ObjectValue(_, ClassBasePointer(className)) =>
          val sootClass = Soot.getSootClass(className)
          val state2 = state.copyState(store = state.newExpr(lhsAddr, sootClass, state.store))

          val expr = new soot.jimple.internal.JSpecialInvokeExpr(
            local, //new soot.jimple.internal.JimpleLocal("newInstanceSnowflake", sootClass.getType()),
            sootClass.getMethod(SootMethod.constructorName, List()).makeRef(),
            List[soot.Value]())

          state2.handleInvoke(expr, None)
        case _ => Set()
      }
    }
  }
}

object Snowflakes {

  def warn(id : Int, stmt : Stmt, meth : SootMethod) {
    Log.warn("Using generic snowflake for Java library in state "+id+". May be unsound. stmt = " + stmt + " method = " + meth)
  }

  val table = mutable.Map.empty[MethodDescription, SnowflakeHandler]
  var initializedObjectValues = Map.empty[String, Store]

  def get(meth : SootMethod) : Option[SnowflakeHandler] =
    table.get(MethodDescription(
      meth.getDeclaringClass.getName,
      meth.getName,
      meth.getParameterTypes.toList.map(_.toString()),
      meth.getReturnType.toString()))

  def contains(meth : MethodDescription) : Boolean =
    table.contains(meth)

  def createArray(t : soot.Type,
                  sizes : List[D],
                  addrs : Set[Addr],
                  bp : BasePointer) : Store = {
    sizes match {
      case Nil => {
        t match {
          case pt: PrimType => Store(mutable.Map(addrs.zipWithIndex.map{case(a,i) => (a, D.atomicTop)}.toMap.toSeq: _*))
          case rt: RefType => {
            val className = rt.getClassName
            val sootClass = Soot.getSootClass(className)
            Store(mutable.Map(addrs.zipWithIndex.map{case(a,i) => (a, D(Set(ObjectValue(sootClass, bp))))}.toMap.toSeq: _*))
          }
        }
      }
      case (s :: ss) => {
        createArray(t.asInstanceOf[ArrayType].getElementType, ss, Set(ArrayRefAddr(bp)), bp)
          .update(addrs, D(Set(ArrayValue(t, bp))))
          .update(ArrayLengthAddr(bp), s).asInstanceOf[Store]
      }
    }
  }

  def createObjectOrThrow(name : String) : D = {
    if (!initializedObjectValues.contains(name)) {
      throw UninitializedSnowflakeObjectException(name)
    }
    D(Set(ObjectValue(Soot.getSootClass(name), SnowflakeBasePointer(name))))
  }

  def createObject(className: String, processing : List[String]) : Store = {
    if (initializedObjectValues.contains(className)) {
      return initializedObjectValues(className)
    }

    val sootClass = Soot.getSootClass(className)
    val fields = sootClass.getFields
    val store = Store(mutable.Map[Addr, D]())

    if (sootClass.hasSuperclass) {
      val newStore = createObject(sootClass.getSuperclass.getName, processing++List(className))
      store.join(newStore).asInstanceOf[Store]
    }

    for (f <- fields) {
      val fieldType = f.getType
      val bp = SnowflakeBasePointer(className)
      val addrs : Set[Addr] = if (f.isStatic) { Set(StaticFieldAddr(f)) }
      else { Set(InstanceFieldAddr(bp, f)) }

      fieldType match {
        case pt: PrimType => store.update(addrs, D.atomicTop).asInstanceOf[Store]
        case at: ArrayType =>
          val dim = List.fill(at.numDimensions)(D.atomicTop)
          store.join(createArray(at, dim, addrs, bp)).asInstanceOf[Store]
        case rt: RefType =>
          val fieldClassName = rt.getClassName
          if (!processing.contains(fieldClassName)) {
            store.update(addrs,
              D(Set(ObjectValue(Soot.getSootClass(fieldClassName), SnowflakeBasePointer(className))))).asInstanceOf[Store]
            val newStore = createObject(fieldClassName, processing++List(className))
            store.join(newStore)
          }
        case _ =>
      }
    }

    initializedObjectValues = initializedObjectValues + (className -> store)
    store
  }

  // For running Image Processor
  table.put(MethodDescription("java.lang.System", "getProperty", List("java.lang.String"), "java.lang.String"),
    ReturnSnowflake(D(Set(ObjectValue(Soot.classes.String, StringBasePointer("returns from getProperty"))))))
  //table.put(MethodDescription("java.nio.file.Paths", "get", List("java.lang.String", "java.lang.String[]"), "java.nio.file.Path"), ReturnObjectSnowflake("java.nio.file.Path"))
  //table.put(MethodDescription("java.util.HashMap", SootMethod.constructorName, List(), "void"),
  //  ReturnObjectSnowflake("java.util.HashMap"))

  // java.io.PrintStream
  table.put(MethodDescription("java.io.PrintStream", "println", List("int"), "void"), NoOpSnowflake)
  table.put(MethodDescription("java.io.PrintStream", "println", List("java.lang.String"), "void"), NoOpSnowflake)

  HashMapSnowflakes // this triggers HashMapSnowflakes to add snowflake entries
  ArrayListSnowflakes
  ClassSnowflakes

  // java.lang.Class
  //private static native void registerNatives();
  //private static native java.lang.Class<?> forName0(java.lang.String, boolean, java.lang.ClassLoader, java.lang.Class<?>) throws java.lang.ClassNotFoundException;
  //public native boolean isInstance(java.lang.Object);
  //public native boolean isAssignableFrom(java.lang.Class<?>);
  //public native boolean isInterface();
  //public native boolean isArray();
  //public native boolean isPrimitive();
  //private native java.lang.String getName0();
  //public native java.lang.Class<? super T> getSuperclass();
  //public native java.lang.Class<?>[] getInterfaces();
  //public native java.lang.Class<?> getComponentType();
  //public native int getModifiers();
  //public native java.lang.Object[] getSigners();
  //native void setSigners(java.lang.Object[]);
  //private native java.lang.Object[] getEnclosingMethod0();
  //private native java.lang.Class<?> getDeclaringClass0();
  //private native java.security.ProtectionDomain getProtectionDomain0();
  //native void setProtectionDomain0(java.security.ProtectionDomain);
  //static native java.lang.Class getPrimitiveClass(java.lang.String);
  //private static native java.lang.reflect.Method getCheckMemberAccessMethod(java.lang.Class<? extends java.lang.SecurityManager>) throws java.lang.NoSuchMethodError;
  //private native java.lang.String getGenericSignature();
  //native byte[] getRawAnnotations();
  //native sun.reflect.ConstantPool getConstantPool();
  //private native java.lang.reflect.Field[] getDeclaredFields0(boolean);
  //private native java.lang.reflect.Method[] getDeclaredMethods0(boolean);
  //private native java.lang.reflect.Constructor<T>[] getDeclaredConstructors0(boolean);
  //private native java.lang.Class<?>[] getDeclaredClasses0();
  table.put(MethodDescription("java.lang.Class", "desiredAssertionStatus", List(), "boolean"), ReturnSnowflake(D.atomicTop))
  //private static native boolean desiredAssertionStatus0(java.lang.Class<?>);

  private def updateStore(oldStore : Store, clas : String, field : String, typ : String) =
    oldStore.update(StaticFieldAddr(Soot.getSootClass(clas).getFieldByName(field)),
      D(Set(ObjectValue(Soot.getSootClass(typ),
        SnowflakeBasePointer(clas + "." + field))))).asInstanceOf[Store]

  table.put(MethodDescription("java.lang.Object", "clone", List(), "java.lang.Object"),
    new NonstaticSnowflakeHandler {
      override def apply(state : State, nextStmt : Stmt, self : Value, args : List[D]) : Set[AbstractState] = {
        val newNewStore = state.stmt.sootStmt match {
          case stmt : DefinitionStmt => state.store.update(state.addrsOf(stmt.getLeftOp), D(Set(self)))
          case stmt : InvokeStmt => state.store
        }
        Set(state.copyState(stmt = nextStmt/*store = newNewStore*/))
      }
    })

  table.put(MethodDescription("java.security.AccessController", "checkPermission", List("java.security.Permission"), "void"), NoOpSnowflake)

  table.put(MethodDescription("java.security.AccessController", "getStackAccessControlContext",
    List(), "java.security.AccessControlContext"), NoOpSnowflake)
  table.put(MethodDescription("java.lang.Class", "registerNatives", List(), "void"), NoOpSnowflake)
  table.put(MethodDescription("sun.misc.Unsafe", "registerNatives", List(), "void"), NoOpSnowflake)

  table.put(MethodDescription("java.lang.Double", "doubleToRawLongBits", List("double"), "long"), ReturnSnowflake(D.atomicTop))
  table.put(MethodDescription("java.lang.Float", "floatToRawIntBits", List("float"), "int"), ReturnSnowflake(D.atomicTop))
  table.put(MethodDescription("java.lang.Class", "isArray", List(), "boolean"), ReturnSnowflake(D.atomicTop))
  table.put(MethodDescription("java.lang.Class", "isPrimitive", List(), "boolean"), ReturnSnowflake(D.atomicTop))

  table.put(MethodDescription("java.lang.Class", "getPrimitiveClass", List("java.lang.String"), "java.lang.Class"),
    ReturnSnowflake(D(Set(ObjectValue(Soot.classes.Class, ClassBasePointer("TODO:unknown"))))))
  table.put(MethodDescription("java.lang.Class", "getComponentType", List(), "java.lang.Class"),
    ReturnSnowflake(D(Set(ObjectValue(Soot.classes.Class, ClassBasePointer("TODO:unknown"))))))

  table.put(MethodDescription("java.security.AccessController", "doPrivileged", List("java.security.PrivilegedAction"), "java.lang.Object"), ReturnObjectSnowflake("java.lang.Object"))

  table.put(MethodDescription("java.lang.System", "arraycopy",
    List("java.lang.Object", "int", "java.lang.Object", "int", "int"), "void"), new StaticSnowflakeHandler {
    override def apply(state : State, nextStmt : Stmt, args : List[D]) : Set[AbstractState] = {
      assert(state.stmt.sootStmt.getInvokeExpr.getArgCount == 5)
      val expr = state.stmt.sootStmt.getInvokeExpr
      val newNewStore = state.store.update(state.addrsOf(expr.getArg(2)), state.eval(expr.getArg(0)))
      Set(state.copyState(stmt = nextStmt/*store = newNewStore*/))
    }
  })

  // java.lang.System
  table.put(MethodDescription("java.lang.System", SootMethod.staticInitializerName, List(), "void"),
    new StaticSnowflakeHandler {
      override def apply(state : State, nextStmt : Stmt, args : List[D]) : Set[AbstractState] = {
        var newNewStore = state.store
        newNewStore = updateStore(newNewStore, "java.lang.System", "in", "java.io.InputStream")
        newNewStore = updateStore(newNewStore, "java.lang.System", "out", "java.io.PrintStream")
        newNewStore = updateStore(newNewStore, "java.lang.System", "err", "java.io.PrintStream")
        newNewStore = updateStore(newNewStore, "java.lang.System", "security", "java.lang.SecurityManager")
        newNewStore = updateStore(newNewStore, "java.lang.System", "cons", "java.io.Console")
        Set(state.copyState(stmt = nextStmt, store = newNewStore))
      }
    })

  //private static native void registerNatives();
  table.put(MethodDescription("java.lang.System", "setIn0", List("java.io.InputStream"), "void"),
    PutStaticSnowflake("java.lang.System", "in"))
  table.put(MethodDescription("java.lang.System", "setOut0", List("java.io.PrintStream"), "void"),
    PutStaticSnowflake("java.lang.System", "out"))
  table.put(MethodDescription("java.lang.System", "setErr0", List("java.io.PrintStream"), "void"),
    PutStaticSnowflake("java.lang.System", "err"))
  table.put(MethodDescription("java.lang.System", "currentTimeMillis", List(), "long"), ReturnSnowflake(D.atomicTop))
  table.put(MethodDescription("java.lang.System", "nanoTime", List(), "long"), ReturnSnowflake(D.atomicTop))
  //public static native void arraycopy(java.lang.Object, int, java.lang.Object, int, int);
  table.put(MethodDescription("java.lang.System", "identityHashCode", List("java.lang.Object"), "int"), ReturnSnowflake(D.atomicTop))
  //private static native java.util.Properties initProperties(java.util.Properties);
  //public static native java.lang.String mapLibraryName(java.lang.String);

  // java.lang.Throwable
  table.put(MethodDescription("java.lang.Throwable", SootMethod.constructorName, List(), "void"), NoOpSnowflake)
  //table.put(MethodDescription("java.lang.Throwable", SootMethod.staticInitializerName, List(), "void"), NoOpSnowflake)
  //private native java.lang.Throwable fillInStackTrace(int);
  table.put(MethodDescription("java.lang.Throwable", "getStackTraceDepth", List(), "int"), ReturnSnowflake(D.atomicTop))
  table.put(MethodDescription("java.lang.Throwable", "fillInStackTrace", List("int"), "java.lang.Throwable"), ReturnObjectSnowflake("java.lang.Throwable"))

  //native java.lang.StackTraceElement getStackTraceElement(int);

  // java.util.ArrayList
  //table.put(MethodDescription("java.util.ArrayList", SootMethod.constructorName, List("int"), "void"), NoOpSnowflake)
}
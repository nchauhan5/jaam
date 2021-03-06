package org.ucombinator.jaam.serializer

/******************************************************************
 * This library handles reading and writing ".jaam" files.
 *
 * See `PacketInput` and `PacketOutput` in this package for usage.
 * ****************************************************************/

import java.io.{FileInputStream, IOException, InputStream, OutputStream}
import java.lang.reflect.Type
import java.util.zip.{DeflaterOutputStream, InflaterInputStream}

import scala.collection.{immutable, mutable}
import scala.collection.JavaConverters._

import com.esotericsoftware.kryo.{Kryo, Registration}
import com.esotericsoftware.kryo
import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.serializers.FieldSerializer
import com.strobel.decompiler.languages.java.ast.AstNode
import com.strobel.decompiler.patterns.Role
import com.twitter.chill.{AllScalaRegistrar, KryoBase}
import de.javakaffee.kryoserializers.UnmodifiableCollectionsSerializer
import org.objectweb.asm.tree.{AbstractInsnNode, InsnList}
import org.objenesis.strategy.StdInstantiatorStrategy
import soot.{Local, SootMethod}
import soot.jimple.{Constant, InvokeExpr, Ref, Stmt => SootStmt}
import soot.util.Chain

object Serializer {
  def readAll(file: String): java.util.List[Packet] = {
    val stream = new FileInputStream(file)
    val pi = new PacketInput(stream)

    var packet: Packet = null
    var packets: List[Packet] = List.empty
    while ({packet = pi.read(); !packet.isInstanceOf[EOF]}) {
      // TODO: for (packet <- pi) {
      packets +:= packet
    }

    packets.reverse.asJava
  }
}

////////////////////////////////////////
// 'PacketInput' is used to read a ".jaam" file
//
// Usage of this class:
//   in = new PacketInput(new FileInputStream("<filename>.jaam"))
//   in.read()
class PacketInput(private val input : InputStream) {
  // Reads a 'Packet'
  // TODO: check for exceptions
  def read() : Packet = {
    this.kryo.readClassAndObject(in) match {
      case o : Packet => o
      case o          => throw new IOException(f"Read object is not a Packet: $o")
    }
  }

  // Closes this 'PacketInput'
  def close(): Unit = in.close()

  ////////////////////////////////////////
  // Implementation internals
  ////////////////////////////////////////

  checkHeader("Jaam file-format signature", Signatures.formatSignature)
  checkHeader("Jaam file-format version", Signatures.formatVersion)

  private val in = new Input(new InflaterInputStream(input))
  private val kryo : Kryo = new JaamKryo()

  private def checkHeader(name : String, expected : Array[Byte]) {
    val found = new Array[Byte](expected.length)
    val len = input.read(found, 0, found.length)

    if (len != expected.length) {
      throw new IOException(
        f"Reading $name yielded only $len bytes. Expected ${expected.length} bytes.")
    }

    if (found.toList != expected.toList) {
      val e = expected.map(_.toHexString).mkString("")
      val f = found.map(_.toHexString).mkString("")
      throw new IOException(f"Invalid $name\n Expected: 0x$e\n Found:    0x$f")
    }
  }
}

////////////////////////////////////////
// 'PacketOutput' is used to write ".jaam" files
//
// Usage of this class:
//   out = new PacketOutput(new FileOutputStream("<filename>.jaam"))
//   out.write(packet)
class PacketOutput(private val output : OutputStream) {
  // Writes a 'Packet'
  def write(m : Packet) : Unit = {
    this.kryo.writeClassAndObject(this.out, m)
  }

  // Flushes output
  def flush(): Unit = out.flush()

  // Closes this 'PacketOutput'
  def close() : Unit = {
    this.write(EOF())
    out.close()
  }

  ////////////////////////////////////////
  // Implementation internals
  ////////////////////////////////////////

  output.write(Signatures.formatSignature)
  output.write(Signatures.formatVersion)

  private val out = new Output(new DeflaterOutputStream(output))
  private val kryo : Kryo = new JaamKryo()
}

private[this] object Signatures {
  // File signature using the same style as PNG
  // \x008a = 'J' + 0x40: High bit set so 'file' knows we are binary
  // JAAM: Help humans figure out what format the file is
  // \r\n: Detect bad line conversion
  // \x001a = '^Z': Stops output on DOS
  // \n: Detect bad line conversion
  val formatSignature = "\u008aJAAM\r\n\u001a\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)
  val formatVersion = { val version = 1; java.nio.ByteBuffer.allocate(4).putInt(version).array() }
}


////////////////////////////////////////
// Packet types
////////////////////////////////////////

// The super type of all packets
abstract class Packet

// Signals that all packets are done
// TODO: check if "object" is okay here
case class EOF () extends Packet

// Declare a transition edge between two 'State' nodes
case class Edge(id : Id[Edge], src : Id[Node], dst : Id[Node]) extends Packet

// Declare the Node - the counterpart to the edge
abstract class Node(val id : Id[Node]) extends Packet

// Declare 'AbstractState' nodes
class AbstractState(override val id : Id[Node]) extends Node(id)

// AnalysisNodes from the analyzer
case class AnalysisNode(var node : Node = null,
                        override val id : Id[Node],
                        abstNodes : mutable.MutableList[Int],
                        inEdges : mutable.MutableList[Int],
                        outEdges : mutable.MutableList[Int],
                        tag : Tag) extends Node(id)

case class ErrorState(override val id : Id[Node]) extends AbstractState(id)
case class State(override val id : Id[Node], stmt : Stmt, framePointer : String, kontStack : String) extends AbstractState(id)

// Declare 'MissingState' nodes, used by jaam.tools.Validate
case class MissingReferencedState(override val id : Id[Node]) extends Node(id)

//case class Group(id : Id[Node], states : java.util.List[Node], labels : String)

//tags for the analyzer
case class NodeTag(id : Id[Tag], node : Id[Node], tag : Tag) extends Packet
abstract class Tag
case class AllocationTag(sootType : soot.Type) extends Tag
case class ChainTag() extends Tag

abstract class LoopNode extends Packet
case class LoopLoopNode(id: Id[LoopNode], method: SootMethod, depends: Set[TaintAddress], statementIndex: Int) extends LoopNode
case class LoopMethodNode(id: Id[LoopNode], method: SootMethod) extends LoopNode
case class LoopEdge(src: Id[LoopNode], dst: Id[LoopNode], isRecursion: Boolean) extends Packet

////////////////////////////////////////
// Types inside packets
////////////////////////////////////////

// Identifiers qualified by a namespace
case class Id[Namespace](id : Int) {
  // val namespace = classOf[Namespace]
}

// Type for statements (needed because 'SootStmt' doesn't specify the
// 'SootMethod' that it is in)
case class Stmt(method : SootMethod, index : Int, stmt : SootStmt)

// Type for taint addresses
abstract sealed class TaintValue

abstract sealed class TaintAddress extends TaintValue {
  val m: SootMethod
}
case class LocalTaintAddress(override val m: SootMethod, local: Local)
  extends TaintAddress
case class RefTaintAddress(override val m: SootMethod, ref: Ref)
  extends TaintAddress
case class ThisRefTaintAddress(override val m: SootMethod) extends TaintAddress
case class ParameterTaintAddress(override val m: SootMethod, index: Int)
  extends TaintAddress
case class ConstantTaintAddress(override val m: SootMethod, c: Constant)
  extends TaintAddress
// case class ConstantTaintAddress(override val m: SootMethod)
  // extends TaintAddress
case class InvokeTaintAddress(override val m: SootMethod, ie: InvokeExpr)
  extends TaintAddress

////////////////////////////////////////
// Internal classes
////////////////////////////////////////

// Internal Kryo object that adds extra checking of the types and field
// structures of read and written objects to be sure they match what was
// expected.
class JaamKryo extends KryoBase {
  var seenClasses = Set.empty[Class[_]]

  // This is copied from Chill
  this.setRegistrationRequired(false)
  this.setInstantiatorStrategy(new StdInstantiatorStrategy)
  // Handle cases where we may have an odd classloader setup like with libjars
  // for hadoop
  val classLoader = Thread.currentThread.getContextClassLoader
  this.setClassLoader(classLoader)
  (new AllScalaRegistrar)(this)
  this.setAutoReset(false)

  // Produces a string that documents the field structure of 'typ'
  def classSignature(typ : Type) : String = {
    typ match {
      case null => ""
      case typ : Class[_] =>
        "  "+typ.getCanonicalName + "\n" +
         (for (i <- typ.getDeclaredFields.toList.sortBy(_.toString)) yield {
          "   "+i+"\n"
         }).mkString("") +
         classSignature(typ.getGenericSuperclass)
      case _ => ""
    }
  }

  override def writeClass(output : Output, t : Class[_]) : Registration = {
    val r = super.writeClass(output, t)

    if (r == null || seenClasses.contains(r.getType)) {
      output.writeString(null)
    } else {
      seenClasses += r.getType
      output.writeString(classSignature(r.getType))
    }

    return r
  }

  override def readClass(input : Input) : Registration = {
    val r = super.readClass(input)

    val found = input.readString()

    if (r == null) { return null }

    if (found != null) {
      val expected = classSignature(r.getType)

      if (expected != found) {
        throw new IOException(f"Differing Jaam class signatures\n Expected:\n$expected Found:\n$found")
      }
    }

    return r
  }

  //********************************
  // Custom serializers
  //********************************

  override def newDefaultSerializer(typ : Class[_]) : kryo.Serializer[_] = {
    if (classOf[InsnList] == typ) {
      // We can't use addDefaultSerializer due to shading in the assembly (TODO: check if still true)
      InsnListSerializer
    } else if (classOf[AbstractInsnNode].isAssignableFrom(typ)) {
      // Subclasses of AbstractInsnNode should not try to serialize prev or
      // next. However, this requires working around a bug in
      // rebuildCachedFields. (See AbstractInsnNodeSerializer.)
      val s = new AbstractInsnNodeSerializer(typ)
      s.removeField("prev")
      s.removeField("next")
      s
    } else if (classOf[AstNode].isAssignableFrom(typ)) {
      new ProcyonRoleSerializer(typ)
    } else {
      super.newDefaultSerializer(typ)
    }
  }

  UnmodifiableCollectionsSerializer.registerSerializers(this)
  this.addDefaultSerializer(classOf[Chain[Object]], classOf[FieldSerializer[java.lang.Object]])
  this.addDefaultSerializer(com.strobel.core.ArrayUtilities.asUnmodifiableList().getClass, UnmodifiableListSerializer)

  // Force these to be field serializer instead of collection serializer
  for (c <- Seq(
    classOf[com.strobel.assembler.metadata.ParameterDefinitionCollection],
    classOf[com.strobel.assembler.metadata.GenericParameterCollection],
    classOf[com.strobel.assembler.metadata.AnonymousLocalTypeCollection],
    classOf[com.strobel.assembler.metadata.VariableDefinitionCollection])) {
    this.addDefaultSerializer(c, new FieldSerializer(this, c))
  }

  // Register those Charset classes which are not public.
  // See https://github.com/jagrutmehta/kryo-UTF/
  // Run the following to determine what classes need to be here:
  //   for i in ../rt.jar/sun/nio/cs/*.class; do javap $i; done|grep ' extends '|grep -v '^public'
  for (name <- Seq("UTF-8", "UTF-16", "UTF-16BE", "UTF-16LE", "x-UTF-16LE-BOM", "ISO_8859_1" /*, "US_ASCII"*/)) {
    this.addDefaultSerializer(java.nio.charset.Charset.forName(name).getClass, CharsetSerializer)
  }

  object CharsetSerializer extends kryo.Serializer[java.nio.charset.Charset] {
    override def write(kryo: Kryo, output: Output, obj: java.nio.charset.Charset) {
      kryo.writeObject(output, obj.asInstanceOf[java.nio.charset.Charset].name())
    }

    override def read(kryo: Kryo, input: Input, typ: Class[java.nio.charset.Charset]): java.nio.charset.Charset =
      java.nio.charset.Charset.forName(kryo.readObject(input, classOf[String]))
  }

  // Serializer for InsnList that avoids stack overflows due to recursively
  // following AbstractInsnNode.next.  This works on concert with
  // AbstractInsnNodeSerializer.
  object InsnListSerializer extends kryo.Serializer[InsnList] {
    override def write(kryo : Kryo, output : Output, collection : InsnList): Unit = {
      output.writeVarInt(collection.size(), true)
      for (element <- collection.iterator.asScala)
        kryo.writeClassAndObject(output, element)
    }

    override def read(kryo : Kryo, input : Input, typ : Class[InsnList]) : InsnList = {
      val collection = new InsnList()
      kryo.reference(collection)

      (0 until input.readVarInt(true)) foreach {
        _ => collection.add(kryo.readClassAndObject(input).asInstanceOf[AbstractInsnNode])
      }

      collection
    }
  }

  // FieldSerializer.rebuildCachedFields has bugs relating to removed fields.
  // The following implementation works around these
  class AbstractInsnNodeSerializer[T](typ : Class[T])
      extends FieldSerializer(this, typ) {
    override def rebuildCachedFields(minorRebuild : Boolean) {
      // Save and clear removedFields since the below calls to removeField
      // will repopulate it.  Otherwise, we get a ConcurrentModificationException.
      val removed = this.removedFields
      if (!minorRebuild) {
        this.removedFields = new java.util.HashSet()
      }
      super.rebuildCachedFields(minorRebuild)
      if (!minorRebuild) {
        for (field <- removed.asScala) {
          // Make sure to use toString otherwise you call a version of
          // removeField that uses pointer equality and thus no effect
          removeField(field.toString)
        }
      }
    }
  }

  object UnmodifiableListSerializer extends kryo.Serializer[java.util.AbstractList[Object]] {
    override def write(kryo: Kryo, output: Output, obj: java.util.AbstractList[Object]) {
      kryo.writeObject(output, new java.util.ArrayList[Object](obj))
    }

    override def read(kryo: Kryo, input: Input, typ: Class[java.util.AbstractList[Object]]): java.util.AbstractList[Object] =
      com.strobel.core.ArrayUtilities.asUnmodifiableList[Object](kryo.readObject(input, classOf[java.util.ArrayList[Object]]).asScala:_*).asInstanceOf[java.util.AbstractList[Object]]
  }

  // Procyon keeps a global table of Role objects, so we have to deserialize
  // into the objects in that table.  This is complicated by the fact that it
  // never stores direct references to those objects.  It just stores the
  // index of that object into the table.  Fortunately, AstNode seems to be
  // the only place that uses Role objects.
  class ProcyonRoleSerializer[T](typ: Class[T])
      extends FieldSerializer[T](this, typ) {
    override def write(kryo : Kryo, output : Output, obj : T) {
      super.write(kryo, output, obj)
      val role = obj.asInstanceOf[AstNode].getRole
      output.writeAscii(role.getNodeType.toString)
      output.writeAscii(role.toString)
    }

    override def read(kryo : Kryo, input : Input, typ : Class[T]) : T = {
      val obj = super.read(kryo, input, typ)
      val nodeType = input.readString()
      val name = input.readString()
      roles.get((nodeType,name)) match {
        case None =>
          throw new Exception(f"Error during deserialize a $typ: could not find Role for $nodeType and $name")
        case Some(index) => obj.asInstanceOf[AstNode].setRole(Role.get(index))
      }
      return obj
    }
  }

  val roles: immutable.Map[(String, String), Int] = {
    // Ensure that all Role objects have been put in the global table
    val classes = List(
      "com.strobel.decompiler.languages.java.ast.ArrayCreationExpression",
      "com.strobel.decompiler.languages.java.ast.AssertStatement",
      "com.strobel.decompiler.languages.java.ast.AssignmentExpression",
      "com.strobel.decompiler.languages.java.ast.AstNode",
      "com.strobel.decompiler.languages.java.ast.BinaryOperatorExpression",
      "com.strobel.decompiler.languages.java.ast.BlockStatement",
      "com.strobel.decompiler.languages.java.ast.BreakStatement",
      "com.strobel.decompiler.languages.java.ast.CaseLabel",
      "com.strobel.decompiler.languages.java.ast.CatchClause",
      "com.strobel.decompiler.languages.java.ast.ClassOfExpression",
      "com.strobel.decompiler.languages.java.ast.CompilationUnit",
      "com.strobel.decompiler.languages.java.ast.ComposedType",
      "com.strobel.decompiler.languages.java.ast.ConditionalExpression",
      "com.strobel.decompiler.languages.java.ast.ContinueStatement",
      "com.strobel.decompiler.languages.java.ast.DoWhileStatement",
      "com.strobel.decompiler.languages.java.ast.EntityDeclaration",
      "com.strobel.decompiler.languages.java.ast.ForEachStatement",
      "com.strobel.decompiler.languages.java.ast.ForStatement",
      "com.strobel.decompiler.languages.java.ast.GotoStatement",
      "com.strobel.decompiler.languages.java.ast.IfElseStatement",
      "com.strobel.decompiler.languages.java.ast.ImportDeclaration",
      "com.strobel.decompiler.languages.java.ast.InstanceOfExpression",
      "com.strobel.decompiler.languages.java.ast.LambdaExpression",
      "com.strobel.decompiler.languages.java.ast.MethodDeclaration",
      "com.strobel.decompiler.languages.java.ast.MethodGroupExpression",
      "com.strobel.decompiler.languages.java.ast.ObjectCreationExpression",
      "com.strobel.decompiler.languages.java.ast.ReturnStatement",
      "com.strobel.decompiler.languages.java.ast.Roles",
      "com.strobel.decompiler.languages.java.ast.SwitchSection",
      "com.strobel.decompiler.languages.java.ast.SwitchStatement",
      "com.strobel.decompiler.languages.java.ast.SynchronizedStatement",
      "com.strobel.decompiler.languages.java.ast.ThrowStatement",
      "com.strobel.decompiler.languages.java.ast.TryCatchStatement",
      "com.strobel.decompiler.languages.java.ast.UnaryOperatorExpression",
      "com.strobel.decompiler.languages.java.ast.WhileStatement",
      "com.strobel.decompiler.languages.java.ast.WildcardType"
    )
    classes.foreach(Class.forName)

    // TODO: refactor
    // Build a mapping from nodeType and name to a Role index
    (0 until (1 << Role.ROLE_INDEX_BITS)).
      map(i => (i, Role.get(i))).
      withFilter(_._2 != null).
      map {
        case (i, r) => (r.getNodeType.toString, r.toString) -> i
      }.toMap
  }
}

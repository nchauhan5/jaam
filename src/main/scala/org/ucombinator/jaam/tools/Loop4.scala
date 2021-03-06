package org.ucombinator.jaam.tools.loop4

import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.PrintStream
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import org.jgrapht.Graphs
import org.ucombinator.jaam.tools
import soot.{Main => SootMain, Unit => SootUnit, Value => SootValue, _}
import soot.options.Options
import soot.jimple.{Stmt => SootStmt, _}
import org.ucombinator.jaam.util.JGraphT
import org.ucombinator.jaam.util.Stmt
import org.ucombinator.jaam.util.Soot
import org.ucombinator.jaam.tools.app.{Origin, App}
import org.ucombinator.jaam.tools.coverage2.Coverage2
import org.ucombinator.jaam.serializer.Serializer

import scala.collection.immutable
import scala.collection.mutable

import scala.collection.JavaConverters._
import org.ucombinator.jaam.util.Soot

object Main {
  def main(input: List[String], jaam: String, prune: Boolean, shrink: Boolean, prettyPrint: Boolean) {
    Options.v().set_verbose(false)
    Options.v().set_output_format(Options.output_format_jimple)
    Options.v().set_keep_line_number(true)
    Options.v().set_allow_phantom_refs(true)
    //Options.v().set_soot_classpath(classpath.mkString(":"))
    Options.v().set_include_all(true)
    Options.v().set_prepend_classpath(false)
    Options.v().set_src_prec(Options.src_prec_only_class)
    //Options.v().set_whole_program(true)-
    //Options.v().set_app(true)-
    soot.Main.v().autoSetOptions()

    //Options.v().setPhaseOption("cg", "verbose:true")
    //Options.v().setPhaseOption("cg.cha", "enabled:true")

    //Options.v.set_main_class(mainClass)
    //Scene.v.setMainClass(clazz)
    //Scene.v.addBasicClass(className, SootClass.HIERARCHY)
    //Scene.v.setSootClassPath(classpath)
    //Scene.v.loadNecessaryClasses

    Soot.useJaamClassProvider()

    val inputPackets = input.flatMap(Serializer.readAll(_).asScala)

    for (a <- inputPackets) { Soot.addClasses(a.asInstanceOf[App]) }

    val mainClasses = for (a <- inputPackets) yield { a.asInstanceOf[App].main.className }
    val mainMethods = for (a <- inputPackets) yield { a.asInstanceOf[App].main.methodName }
    val mainClass = mainClasses.head.get // TODO: fix
    val mainMethod = mainMethods.head.get // TODO: fix

    Scene.v.loadBasicClasses()
    PackManager.v.runPacks()

    //println("dc" + Scene.v.dynamicClasses().asScala)
    println("ap" + Scene.v.getApplicationClasses.asScala)
    println("al" + Scene.v.getClasses.asScala)
    println("dan" + Scene.v.getClasses(SootClass.DANGLING).asScala)
    println("lib" + Scene.v.getLibraryClasses.asScala)
    println("phan" + Scene.v.getPhantomClasses.asScala)

    val c = Soot.getSootClass("java.lang.Object")
    println("hier " + Scene.v.getActiveHierarchy)
    println("hier sub " + Scene.v.getActiveHierarchy.getSubclassesOf(c))
    println("fast hier " + Scene.v.getOrMakeFastHierarchy)
    println("hier sub " + Scene.v.getFastHierarchy.getSubclassesOf(c))
    println("entry " + Scene.v.getEntryPoints.asScala)
    //println("main " + Scene.v.getMainClass())
    println("pkg " + Scene.v.getPkgList)

    var class_count = 0
    var method_count = 0
    var stmt_count = 0
    var target_count = 0

    var edges: immutable.Map[Stmt, Set[SootMethod]] = Map.empty

    def invokeExprTargets(expr: InvokeExpr): Set[SootMethod] = {
      val m = expr.getMethod
      val c = m.getDeclaringClass
      val cs: Set[SootClass] = expr match {
        case _ : DynamicInvokeExpr =>
          // Could only come from non-Java sources
          throw new Exception(s"Unexpected DynamicInvokeExpr: $expr")
        case _ : StaticInvokeExpr => Set(c)
        // SpecialInvokeExpr is also a subclasses of InstanceInvokeExpr but we need to treat it special
        case _: SpecialInvokeExpr => Set(c)
        case _: InstanceInvokeExpr =>
          if (c.isInterface) {
            // TODO: Main performance cost, but can't cache because new new hierarchy when scene changes (due to getSootClass?)
            Scene.v.getActiveHierarchy.getImplementersOf(c).asScala.toSet
          } else {
            Scene.v.getActiveHierarchy.getSubclassesOfIncluding(c).asScala.toSet
          }
      }

      cs
        .withFilter(!_.isInterface)
        .map(_.getMethodUnsafe(m.getNumberedSubSignature))
        .filter(_ != null)
    }

    def stmtTargets(stmt: Stmt): Set[SootMethod] = stmt.sootStmt match {
      case s: InvokeStmt => invokeExprTargets(s.getInvokeExpr)
      case s: DefinitionStmt =>
        s.getRightOp match {
          case s: InvokeExpr => invokeExprTargets(s)
          case _ => Set.empty
        }
      case _ => Set.empty
    }

    // Get all classes loaded so Soot doesn't keep recomputing the Hierarchy
    for (name <- Soot.loadedClasses.keys) {
      Soot.getSootClass(name)
      println(f"class name: $name")
    }

    for (name <- Soot.loadedClasses.keys) {
      class_count += 1
      //val name = entry.getName.replace("/", ".").replaceAll("\\.class$", "")
      println(f"class origin ${Soot.loadedClasses(name).origin} $class_count: $name")

      if (Soot.loadedClasses(name).origin == Origin.APP) {
        val c = Soot.getSootClass(name)
        // The .toList prevents a concurrent access exception
        for (m <- c.getMethods.asScala.toList) {
          method_count += 1
          //println(f"method $method_count: $m")
          if (m.isNative) { println("skipping body because native") }
          else if (m.isAbstract) { println("skipping body because abstract") }
          else {
            for (sootStmt <- Soot.getBody(m).getUnits.asScala) {
              stmt_count += 1
              //println(f"stmt $stmt_count: $sootStmt")
              val s = Stmt(Soot.unitToStmt(sootStmt), m)
              val ts = stmtTargets(s)
              target_count += ts.size
              if (edges.contains(s)) {
                println(f"already have edge from $s")
              }
              edges += s -> ts
              //edges += s -> (ts ++ edges.get(s).getOrElse(Set()))
              // TODO: cache ts
              if (ts.nonEmpty) {
                //println(f"$target_count.$c.$m.${s.index}: $ts")
              }
            }
          }
          //println(f"end method $c $m")
        }
        println(f"end class $c")
      }
    }

    var edge_count = 0
    var app_out_count = 0
    var app_in_count = 0
    var app_edge_count = 0
    var app_edge_both_count = 0
    for ((s: Stmt, ds: Set[SootMethod]) <- edges) {
      for (d <- ds) {
        edge_count += 1
        var isAppOut = false
        var isAppIn = false
        Soot.loadedClasses.get(s.sootMethod.getDeclaringClass.getName) match {
          case None => println(f"couldn't find src: " + s.sootMethod.getDeclaringClass.getName + "::" + s)
          case Some(r) =>
            //println(f"found src $r $s")
            if (r.origin == Origin.APP) {
              app_out_count += 1
              isAppOut = true
            }
        }

        Soot.loadedClasses.get(d.getDeclaringClass.getName) match {
          case None => println(f"couldn't find dst: " + d.getDeclaringClass.getName + "::" + d)
          case Some(r) =>
            //println(f"found dst $r $d")
            if (r.origin == Origin.APP) {
              app_in_count += 1
              isAppIn = true
            }
        }

        if (isAppOut || isAppIn) {
          app_edge_count += 1
        }

        if (isAppOut && isAppIn) {
          app_edge_both_count += 1
        }
      }
    }

    println(f"counts: $edge_count $app_out_count $app_in_count $app_edge_count $app_edge_both_count")
    // TODO: computer coverage
    // TODO: process only app methods in the first place

    println(f"END classes=$class_count methods=$method_count stmts=$stmt_count targets=$target_count")

    val outStream = new PrintStream(new FileOutputStream("loop4.out.out")) // or System.out
    val coverageStream = new PrintStream(new FileOutputStream("loop4.coverage.out"))

    // TODO: I don't like the implementation of `appEdges` -- with my first glance I can't get an intuitive sense of it!
    val appEdges =
      for ((s, ds) <- edges;
           Some(c) = Soot.loadedClasses.get(s.sootMethod.getDeclaringClass.getName);
           if c.origin == Origin.APP;
           new_ds = for (d <- ds;
                         Some(c2) = Soot.loadedClasses.get(d.getDeclaringClass.getName);
                         if c2.origin == Origin.APP) yield { d };
           if new_ds.nonEmpty)
        yield { s -> new_ds }

    var appEdges2: Map[SootMethod, Map[Stmt, Set[SootMethod]]] = Map.empty
    for ((s, ds) <- appEdges) {
      val old = appEdges2.getOrElse(s.sootMethod, Map.empty[Stmt, Set[SootMethod]])
      appEdges2 += s.sootMethod -> (old + (s -> ds))
    }

    val targets = (for ((_, s) <- appEdges2; (_, ms) <- s; m <- ms) yield m).toSet
    val roots = appEdges2.keys.filter(!targets.contains(_)).toSet

    roots.
      map("root: " + _).
      foreach(println)

    println(f"appEdges: ${appEdges.size}")

    def encode(s: String): String = s.replace("\"", "\\\"")
    def quote(s: String): String = "\"" + encode(s) + "\""

    //    println("digraph loops {")
    //    println("ranksep=\"10\";");
    //    for ((s, ds) <- appEdges) {
    //      for (d <- ds) {
    //        println(f"  ${quote(s.sootMethod.toString)} -> ${quote(d.toString)};")
    //      }
    //    }
    //    println("}")

    val m = Soot.getSootClass(mainClass).getMethodByName(mainMethod) //Coverage2.freshenMethod(Soot.getSootClass(mainClass).getMethodByName(mainMethod))
    val s = Stmt(Soot.getBody(m).getUnits.asScala.toList.head.asInstanceOf[SootStmt], m)
    val fromMain = appEdges2.getOrElse(m, Map.empty)
    appEdges2 += m -> (fromMain + (s -> (fromMain.getOrElse(s, Set.empty) ++ roots)))

//    computeLoopGraph(mainClass, mainMethod, /*classpath: String,*/
//      outStream, coverageStream, jaam, prune, shrink, prettyPrint, m, appEdges2)
    myLoops(m)
  }

  // TEST CMD: (cd ../..; sbt assembly) && jaam loop4 --input DoWhileLoop.app.jaam --output /dev/null
  def myLoops(m: SootMethod): Unit = {
// TODO: replace set with ordered set?
    val (start, graph) = Soot.getBodyGraph(m)

    println(f"start: ${start.index}: $start\n")
    println(f"graph:\n")
    for (v <- graph.vertexSet.asScala) {
      println(f"  vertex: ${v.index}: $v")
      for (p <- Graphs.successorListOf(graph, v).asScala) {
        println(f"    ${p.index}: $p")
      }
    }
    println()

    val dom = JGraphT.dominators(graph, start)

    println(f"dom:\n")
    for ((k, vs) <- dom) {
      println(f"  key: ${k.index}: $k")
      for (v <- vs) {
        println(f"    ${v.index}: $v")
      }
    }
    println()

    // Maps header nodes to sets of backjump nodes
    val headers = JGraphT.loopHeads(graph, start)

    println(f"headers:\n")
    for ((k, vs) <- headers) {
      println(f"  key: $k")
      for (v <- vs) {
        println(f"    $v")
      }
    }
    println()

    val loops = JGraphT.loopNodes(graph, start)

    println(f"loops:\n")
    for ((k, vs) <- loops) {
      println(f"  key: $k")
      for (v <- vs) {
        println(f"    $v")
      }
    }
    println()

    for ((k, vs) <- loops) {
      println(f"loop at $k")

      // Nodes one past the end
      val ends = vs.flatMap(v => Graphs.successorListOf(graph, v).asScala.filter(s => !vs.contains(s)))
      println(f"  loop.end $ends")

      println(f"  body.start: $k")
//      val dom = JGraphT.dominators(graph, k)

      // TODO: can't use `start` in general.  this is just a hack to allow us to test things
      val pseudoHeader: Stmt = start //null //new PseudoStmt(k)
      println(f"  pseudoHeader: ${pseudoHeader == k}: $pseudoHeader")
//      println(f"  clone: ${k == k.asInstanceOf[AbstractUnit].clone}")
      graph.addVertex(pseudoHeader)
      for (backedge_node <- headers(k)) {
        println(f"  backedge_node: $backedge_node")
        graph.removeEdge(backedge_node, k)
        graph.addEdge(backedge_node, pseudoHeader)
      }

      val loopDom = JGraphT.dominators(graph, k)

      val dom_ends = ends.map(e => loopDom(e))
      println(f"  dom_end: $dom_ends")
      val dom_start = loopDom(pseudoHeader)
      println(f"  dom_start: $dom_start")
//      dom(s)
    }

  }

  // Copied from Loop2.main
  def computeLoopGraph(mainClass: String,
                       mainMethod: String, /*classpath: String,*/
                       graphStream: PrintStream,
                       coverageStream: PrintStream,
                       jaam: String,
                       prune: Boolean,
                       shrink: Boolean,
                       prettyPrint: Boolean,
                       m: SootMethod,
                       cg: Map[SootMethod, Map[Stmt, Set[SootMethod]]]): Unit = {
    // import org.ucombinator.jaam.tools.LoopAnalyzer
    import org.ucombinator.jaam.serializer

    val graph = makeLoopGraph(m, cg, prettyPrint)
    val pruned = if (prune) graph.prune else  graph
    val shrunk = if (shrink) {
      pruned // Was: pruned.shrink // TODO: shrink currently goes in an infinite loop 
    } else {
      pruned
    }

    // TODO: print unpruned size
    val outSerializer = new serializer.PacketOutput(new FileOutputStream(jaam))
    shrunk.toJaam(outSerializer)
    outSerializer.close()

    Console.withOut(graphStream) {
      println("digraph loops {")
      println("ranksep=\"10\";")
      print(shrunk)
      println("}")
    }

    //    Console.withOut(coverageStream) {
    //      tools.LoopAnalyzer.computeCoverage(classpath, graph)
    //    }
  }

  // Copied from Loop2.LoopGraph.apply
  def makeLoopGraph(m: SootMethod,
                    cg: Map[SootMethod,Map[Stmt, Set[SootMethod]]],
                    prettyPrint: Boolean): tools.LoopAnalyzer.LoopGraph = {
    import tools.LoopAnalyzer._
    import tools.LoopAnalyzer.LoopGraph._

    // TODO if things get slow, this should be easy to optimize
    def build(m: SootMethod, g: Map[Node, Set[Node]]):
    Map[Node, Set[Node]] = {

      val mNode = MethodNode(m)
      if (g isDefinedAt mNode) {
        g
      } else {
        //val iterator = ??? //cg.edgesOutOf(m)
        val forest = getLoopForest(m)
        if (prettyPrint) {
          for (tree <- forest) {
            println("Tree:")
            tree.prettyPrint()
          }
        }
        // g keeps track of the methods we've seen, so adding the empty set
        // to it prevents an infinite loop.
        var newGraph: Map[Node, Set[Node]] = g + (mNode -> Set.empty)
        newGraph = addForest(g, mNode, forest, m)
        for ((stmt, methods) <- cg.getOrElse(m, Map.empty)) {
          for (tgt <- methods) {
            println(f"src $stmt tgt $tgt")
            val sootStmt = stmt.sootStmt
            val dest = tgt

            // class initializers can't recur but Soot thinks they do
            if (m.getSignature != dest.getSignature || m.getName != "<clinit>"){
              val destNode = MethodNode(dest)
              val parents = forest filter { _ contains sootStmt }
              if (parents.isEmpty) {
                newGraph = add(newGraph, mNode, destNode)
              } else {
                assert(parents.size == 1, "multiple parents")
                val parent = LoopNode(m, parents.head.parent(sootStmt).loop)
                newGraph = add(newGraph, parent, destNode)
              }
              newGraph = build(dest, newGraph)
            }
          }
        }
        newGraph
      }
    }
    LoopGraph(m, build(m, Map.empty), Set.empty[(Node,Node)])
  }
}

class PseudoStmt(stmt: Stmt) extends Stmt(stmt.sootStmt, stmt.sootMethod) {
  override def toString: String = "PseudoStmt" + super.toString
}

// ./bin/jaam-tools loop4 --classpath ../../engagements/en4/article/stac_engagement_4_release_v1.1/challenge_programs/airplan_1/challenge_program/lib/airplan_1.jar --classpath ../../engagements/en4/article/stac_engagement_4_release_v1.1/challenge_programs/airplan_1/challenge_program/lib/commons-cli-1.3.jar --classpath ../../engagements/en4/article/stac_engagement_4_release_v1.1/challenge_programs/airplan_1/challenge_program/lib/commons-codec-1.9.jar --classpath ../../engagements/en4/article/stac_engagement_4_release_v1.1/challenge_programs/airplan_1/challenge_program/lib/commons-fileupload-1.3.1.jar --classpath ../../engagements/en4/article/stac_engagement_4_release_v1.1/challenge_programs/airplan_1/challenge_program/lib/commons-io-2.2.jar --classpath ../../engagements/en4/article/stac_engagement_4_release_v1.1/challenge_programs/airplan_1/challenge_program/lib/commons-lang3-3.4.jar --classpath ../../engagements/en4/article/stac_engagement_4_release_v1.1/challenge_programs/airplan_1/challenge_program/lib/commons-logging-1.2.jar --classpath ../../engagements/en4/article/stac_engagement_4_release_v1.1/challenge_programs/airplan_1/challenge_program/lib/httpclient-4.5.1.jar --classpath ../../engagements/en4/article/stac_engagement_4_release_v1.1/challenge_programs/airplan_1/challenge_program/lib/httpcore-4.4.3.jar --classpath ../../engagements/en4/article/stac_engagement_4_release_v1.1/challenge_programs/airplan_1/challenge_program/lib/jline-2.8.jar --classpath ../../engagements/en4/article/stac_engagement_4_release_v1.1/challenge_programs/airplan_1/challenge_program/lib/log4j-1.2.17.jar --classpath ../../engagements/en4/article/stac_engagement_4_release_v1.1/challenge_programs/airplan_1/challenge_program/lib/mapdb-2.0-beta8.jar --classpath ../../engagements/en4/article/stac_engagement_4_release_v1.1/challenge_programs/airplan_1/challenge_program/lib/netty-all-4.0.34.Final.jar --classpath ../../engagements/en4/article/stac_engagement_4_release_v1.1/challenge_programs/airplan_1/challenge_program/lib/protobuf-java-3.0.0-beta-2.jar --classpath resources/rt.jar


// --app airplan_1.jar
// --rt ../../../../../../../../jaam/jaam.git/resources/rt.jar
// --lib commons-cli-1.3.jar
// --lib commons-codec-1.9.jar
// --lib commons-fileupload-1.3.1.jar
// --lib commons-io-2.2.jar
// --lib commons-lang3-3.4.jar
// --lib commons-logging-1.2.jar
// --lib httpclient-4.5.1.jar
// --lib httpcore-4.4.3.jar
// --lib jline-2.8.jar
// --lib log4j-1.2.17.jar
// --lib mapdb-2.0-beta8.jar
// --lib netty-all-4.0.34.Final.jar
// --lib protobuf-java-3.0.0-beta-2.jar
// 
// --app airplan_1.jar --rt ../../../../../../../../jaam/jaam.git/resources/rt.jar --lib commons-cli-1.3.jar --lib commons-codec-1.9.jar --lib commons-fileupload-1.3.1.jar --lib commons-io-2.2.jar --lib commons-lang3-3.4.jar --lib commons-logging-1.2.jar --lib httpclient-4.5.1.jar --lib httpcore-4.4.3.jar --lib jline-2.8.jar --lib log4j-1.2.17.jar --lib mapdb-2.0-beta8.jar --lib netty-all-4.0.34.Final.jar --lib protobuf-java-3.0.0-beta-2.jar

package edu.cmu.ml.rtw.pra.graphs

import scala.collection.mutable

import com.mattg.pipeline.Step
import com.mattg.util.Dictionary
import com.mattg.util.ImmutableDictionary
import com.mattg.util.MutableConcurrentDictionary
import com.mattg.util.FileUtil
import com.mattg.util.JsonHelper

import com.typesafe.scalalogging.LazyLogging

import org.json4s._
import org.json4s.native.JsonMethods._

import gnu.trove.{TIntObjectHashMap => TMap}
import gnu.trove.{TIntArrayList => TList}

trait Graph {
  protected def entries: Array[Node]

  val emptyNode = Node(new TMap())

  def getNode(i: Int): Node = {
    if (i < entries.size) {
      entries(i)
    } else {
      emptyNode
    }
  }

  def getNode(name: String): Node = getNode(getNodeIndex(name))

  def getNodeName(i: Int): String
  def getNodeIndex(name: String): Int
  def hasNode(name: String): Boolean
  def getNumNodes(): Int

  def getEdgeName(i: Int): String
  def getEdgeIndex(name: String): Int
  def hasEdge(name: String): Boolean
  def getNumEdgeTypes(): Int

  // These methods below here should only be used with care!  Depending on your graph, they could
  // take up a lot of memory and a lot of time.  The point here is to write these to disk in
  // particular formats, for the occasion that you would like to do this.  It doesn't make much
  // sense if the graph already exists on disk.
  def getAllTriples(): Seq[(Int, Int, Int)] = {
    entries.par.zipWithIndex.flatMap(nodeIndex => {
      val node = nodeIndex._1
      val index = nodeIndex._2
      node.edges.keys.par.flatMap(relation => {
        val relationName = getEdgeName(relation)
        // We only need to do the out edges, because the in edges will be caught as out edges for
        // the other node.
        val outEdges = node.edges.get(relation)._2
        val outEdgeTriples =
          (for (i <- 0 until outEdges.size) yield (index, relation, outEdges.get(i)))
        outEdgeTriples
      })
    }).seq
  }

  def getAllTriplesAsStrings(): Seq[(String, String, String)] = {
    getAllTriples.par.map(triple => {
      (getNodeName(triple._1), getEdgeName(triple._2), getNodeName(triple._3))
    }).seq
  }

  // This takes the graph and writes it to the format that is read by
  // Dataset.lineToInstanceAndGraph.
  def writeToInstanceGraph(): String = {
    val triples = getAllTriplesAsStrings()
    triples.map(triple => triple._1 + "^,^" + triple._2 + "^,^" + triple._3).mkString(" ### ")
  }

  def writeToGraphChiFormat(): String = {
    getAllTriples.map(triple => triple._1 + "\t" + triple._2 + "\t" + triple._3).mkString("\n")
  }

  def writeToGraphChiLines(): Seq[String] = {
    getAllTriples.map(triple => triple._1 + "\t" + triple._2 + "\t" + triple._3).toSeq
  }

  def writeToBinaryFile(filename: String, fileUtil: FileUtil = new FileUtil) {
    val out = fileUtil.getDataOutputStream(filename)
    for ((source, relation, target) <- getAllTriples) {
      out.writeInt(source)
      out.writeInt(target)
      out.writeInt(relation)
    }
    out.close()
  }
}

object Graph {
  implicit val formats = DefaultFormats

  /**
   * Creates a Graph object with the given params (or None, if there is no graph specified).  Note
   * that this MUST be lightweight because of the way it is used in the pipeline architecture -
   * only do object creation in this method, and in the constructors of all Graph objects.  Don't
   * do any processing - make sure that all class members that are expensive to compute are lazy.
   */
  def create(params: JValue, graphBaseDir: String, fileUtil: FileUtil): Option[Graph] = {
    val graphType = JsonHelper.extractWithDefault(params, "type", "default")
    graphType match {
      case "remote" => {
        val hostname = (params \ "hostname").extract[String]
        val port = (params \ "port").extract[Int]
        Some(new RemoteGraph(hostname, port, 60))
      }
      case other => {
        val graphDirectory = params match {
          case JNothing => None
          case JString(path) if (path.startsWith("/")) => Some(path)
          case JString(name) => Some(graphBaseDir + name + "/")
          case jval => Some(graphBaseDir + (jval \ "name").extract[String] + "/")
        }
        val graph = graphDirectory match {
          case None => None
          case Some(dir) => Some(new GraphOnDisk(dir, fileUtil))
        }
        graph
      }
    }
  }

  /**
   * Looks at the parameters and returns the required input graph directory, and the Step that will
   * create it, if any.  This piece of code integrates the Graph code with the pipeline
   * architecture in com.mattg.pipeline.
   *
   * The graph parameters could just be a string; in that case, we just use it as the graph
   * directory and don't try to create anything.  If there are parameters for creating this graph,
   * we return a Step object using those parameters, so the pipeline architecture can make sure the
   * graph is constructed.
   */
  def getStepInput(params: JValue, baseGraphDir: String, fileUtil: FileUtil): Option[(String, Option[Step])] = {
    params match {
      case JString(name) => {
        val graphDir = if (name.startsWith("/")) name else baseGraphDir + name + "/"
        Some((graphDir, None))
      }
      case jval => {
        jval \ "name" match {
          case JString(name) => {
            val graphDir = baseGraphDir + name + "/"
            val creator: Step = new GraphCreator(baseGraphDir, params, fileUtil)
            Some((graphDir, Some(creator)))
          }
          case other => None
        }
      }
    }
  }
}

// The edges map is (relation -> (in edges, out edges)).
case class Node(edges: TMap[(TList, TList)]) {

  // We'll save ourselves some time and memory and only create this when it's asked for.  Hopefully
  // it won't exacerbate memory issues too much.  But, especially when using random walks to
  // compute PPR, this can take a lot of time if it's recomputed every time it's asked for.
  private lazy val _connectedNodes = edges.getValues(new Array[(TList, TList)](0)).flatMap(value => {
    (for (i <- 0 until value._1.size) yield value._1.get(i)) ++
    (for (i <- 0 until value._2.size) yield value._2.get(i))
  }).toSet

  def getAllConnectedNodes(): Set[Int] = _connectedNodes
}

// This Graph implementation is backed by a file on disk, and can either be used with GraphChi or
// loaded into memory.
class GraphOnDisk(
  val graphDir: String,
  fileUtil: FileUtil = new FileUtil
) extends Graph with LazyLogging {
  val (graphFile, fileIsBinary) = {
    if (fileUtil.fileExists(graphDir + "edges.dat")) {
      (graphDir + "edges.dat", true)
    } else {
      (graphDir + "graph_chi/edges.tsv", false)
    }
  }

  lazy val _entries: Array[Node] = if (fileIsBinary) loadGraphFromBinaryFile() else loadGraph()
  lazy val numShards = fileUtil.readLinesFromFile(graphDir + "num_shards.tsv")(0).toInt

  lazy val nodeDict = {
    logger.info("Loading node dictionary")
    ImmutableDictionary.readFromFile(graphDir + "node_dict.tsv", fileUtil)
  }
  lazy val edgeDict = {
    logger.info("Loading edge dictionary")
    ImmutableDictionary.readFromFile(graphDir + "edge_dict.tsv", fileUtil)
  }

  override def entries = _entries
  override def getNodeName(i: Int) = nodeDict.getString(i)
  override def getNodeIndex(name: String) = nodeDict.getIndex(name)
  override def hasNode(name: String) = nodeDict.hasString(name)
  override def getNumNodes() = nodeDict.size

  override def getEdgeName(i: Int) = edgeDict.getString(i)
  override def getEdgeIndex(name: String) = edgeDict.getIndex(name)
  override def hasEdge(name: String) = edgeDict.hasString(name)
  override def getNumEdgeTypes() = edgeDict.size

  def loadGraph(): Array[Node] = {
    logger.info(s"Loading graph, with initial size ${nodeDict.size}")
    val graphBuilder = new GraphBuilder(nodeDict.size, nodeDict, edgeDict)
    logger.info(s"Iterating through file")
    var lineNum = 0
    def addEdge(line: String): Unit = {
      fileUtil.logEvery(1000000, lineNum)
      var source, target, relation = 0
      var i = 0
      while (line.charAt(i) != '\t') {
        source *= 10
        source += line.charAt(i) - 48
        i += 1
      }
      i += 1
      while (line.charAt(i) != '\t') {
        target *= 10
        target += line.charAt(i) - 48
        i += 1
      }
      i += 1
      while (i < line.length) {
        relation *= 10
        relation += line.charAt(i) - 48
        i += 1
      }
      graphBuilder.addEdge(source, target, relation)
      lineNum += 1
    }
    fileUtil.processFile(graphFile, addEdge _)
    logger.info("Done reading graph file")
    graphBuilder.build
  }

  def loadGraphFromBinaryFile(): Array[Node] = {
    logger.info(s"Loading graph from binary file, with initial size ${nodeDict.size}")
    val graphBuilder = new GraphBuilder(nodeDict.size, nodeDict, edgeDict)
    logger.info(s"Iterating through file")
    val in = fileUtil.getDataInputStream(graphFile)
    try {
      while (true) {
        val source = in.readInt()
        val target = in.readInt()
        val relation = in.readInt()
        graphBuilder.addEdge(source, target, relation)
      }
    } catch {
      case e: java.io.EOFException => { }
    }
    in.close()
    logger.info("Done reading graph file")
    graphBuilder.build
  }
}

// This Graph implementation has no corresponding file on disk, so it cannot be used with GraphChi,
// and is only kept in memory.  It must be constructed with the Node array, as there is no way to
// load it lazily.
class GraphInMemory(_entries: Array[Node], nodeDict: Dictionary, edgeDict: Dictionary) extends Graph {
  override def entries = _entries
  override def getNodeName(i: Int) = nodeDict.getString(i)
  override def getNodeIndex(name: String) = nodeDict.getIndex(name)
  override def hasNode(name: String) = nodeDict.hasString(name)
  override def getNumNodes() = nodeDict.size

  override def getEdgeName(i: Int) = edgeDict.getString(i)
  override def getEdgeIndex(name: String) = edgeDict.getIndex(name)
  override def hasEdge(name: String) = edgeDict.hasString(name)
  override def getNumEdgeTypes() = edgeDict.size

  def writeToDisk(directory: String) {
    val fileUtil = new FileUtil
    nodeDict.writeToFile(directory + "node_dict.tsv")
    edgeDict.writeToFile(directory + "node_dict.tsv")
    fileUtil.writeLinesToFile(directory + "edges.tsv", writeToGraphChiLines())
  }
}

// This class constructs a Node array corresponding to a particular graph.  There's a little bit of
// funniness with the dictionaries, because GraphOnDisk only needs the Node array created, while
// GraphInMemory needs the dictionaries too.  So we just make them vals, so the caller can get the
// dictionaries out if necessary.
class GraphBuilder(
  initialSize: Int = -1,
  val nodeDict: Dictionary = new MutableConcurrentDictionary,
  val edgeDict: Dictionary = new MutableConcurrentDictionary
) extends LazyLogging {
  type MutableGraphEntry = TMap[(TList, TList)]
  var entries = new Array[MutableGraphEntry](if (initialSize > 0) initialSize else 100)
  (0 until entries.length).par.foreach(i => { entries(i) = new MutableGraphEntry })
  var maxIndexSeen = -1
  var edgesAdded = 0

  def addEdge(source: String, target: String, relation: String) {
    addEdge(nodeDict.getIndex(source), nodeDict.getIndex(target), edgeDict.getIndex(relation))
  }

  def getOrUpdate(entry: MutableGraphEntry, relation: Int): (TList, TList) = {
    val edges = entry.get(relation)
    if (edges == null) {
      val newEdges = (new TList, new TList)
      entry.put(relation, newEdges)
      newEdges
    } else {
      edges
    }
  }

  def addEdge(source: Int, target: Int, relation: Int) {
    if (source > maxIndexSeen) maxIndexSeen = source
    if (target > maxIndexSeen) maxIndexSeen = target
    // It turns out that calling .size on an array in scala creates a new object.  Who knew?
    if (source >= entries.length || target >= entries.length) {
      growEntries()
    }
    val sourceEdges = getOrUpdate(entries(source), relation)
    sourceEdges._2.add(target)
    val targetEdges = getOrUpdate(entries(target), relation)
    targetEdges._1.add(source)
    edgesAdded += 1
  }

  def growEntries() {
    val oldSize = entries.size
    val newSize = oldSize * 2
    val newEntries = new Array[MutableGraphEntry](newSize)
    Array.copy(entries, 0, newEntries, 0, oldSize)
    (oldSize until newSize).par.foreach(i => { newEntries(i) = new MutableGraphEntry })
    entries = newEntries
  }

  def build(): Array[Node] = {
    logger.info("Building the graph object")
    // If no initial size was provided, we try to trim the size of the resultant array (this should
    // cut down the graph size by at most a factor of 2).  If we were given an initial graph size,
    // then the caller probably knew how big the graph was, and might query for nodes that we never
    // actually saw edges for, and we'll need an empty node representations for that.
    val finalSize = if (initialSize == -1) maxIndexSeen + 1 else entries.length
    val finalized = new Array[Node](finalSize)
    (0 until finalSize).par.foreach(i => {
      if (entries(i) == null) {
        finalized(i) = new Node(new TMap())
      } else {
        finalized(i) = new Node(entries(i))
      }
    })
    logger.info("Graph object built")
    finalized
  }

  def toGraphInMemory(): GraphInMemory = {
    val nodes = build()
    new GraphInMemory(nodes, nodeDict, edgeDict)
  }
}

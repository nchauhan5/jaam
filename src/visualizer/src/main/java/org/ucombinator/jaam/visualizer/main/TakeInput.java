package org.ucombinator.jaam.visualizer.main;

import java.io.*;
import java.util.ArrayList;
import java.util.Stack;

import org.ucombinator.jaam.serializer.*;
import org.ucombinator.jaam.visualizer.graph.Graph;
//import org.ucombinator.jaam.visualizer.graph.Class;
import org.ucombinator.jaam.visualizer.graph.Instruction;

public class TakeInput extends Thread
{
	BufferedReader parseInput;
	PacketInput packetInput;
	public static boolean fakeData = true;

	public void run(String file, boolean fromPackets)
	{
		Main.graph = new Graph();
		this.parsePackets(file);

		/*Main.graph.finalizeParentsForRootChildren();
		Main.graph.mergeAllByMethod();
		Main.graph.computeInstLists();
		Main.graph.collectAllTags();
		Main.graph.identifyLoops();
		Main.graph.calcLoopHeights();*/

		Parameters.stFrame.mainPanel.initFX(null);
		Parameters.mouseLastTime = System.currentTimeMillis();
	}

	public void parsePackets(String file)
	{
		if(file.equals("")) {
			readSmallDummyGraph();
			//readLargeDummyGraph();
		}
		else try
		{
			packetInput = new PacketInput(new FileInputStream(file));
			Packet packet = packetInput.read();

			while(!(packet instanceof EOF))
			{
				//Name collision with our own Edge class
				if(packet instanceof org.ucombinator.jaam.serializer.Edge)
				{
					org.ucombinator.jaam.serializer.Edge edgePacket = (org.ucombinator.jaam.serializer.Edge) packet;
					int edgeId = edgePacket.id().id();
					int srcId = edgePacket.src().id();
					int destId = edgePacket.dst().id();
					Main.graph.addEdge(srcId, destId);
				}
				else if(packet instanceof ErrorState)
				{
					// TODO: Add description for ErrorState on initialization
					int id = ((ErrorState) packet).id().id();
					Main.graph.addErrorState(id);
				}
				//Name collision with java.lang.Thread.State
				else if(packet instanceof org.ucombinator.jaam.serializer.State)
				{
					org.ucombinator.jaam.serializer.State statePacket = (org.ucombinator.jaam.serializer.State) packet;
					int id = statePacket.id().id();
					String methodName = statePacket.stmt().method().toString();
					String instruction = statePacket.stmt().stmt().toString();
					int jimpleIndex = statePacket.stmt().index();
					Instruction inst = new Instruction(instruction, methodName, jimpleIndex, true);
					Main.graph.addVertex(id, inst, true);
				}
                
                else if(packet instanceof org.ucombinator.jaam.serializer.NodeTag)
                {
                    org.ucombinator.jaam.serializer.NodeTag tag = (org.ucombinator.jaam.serializer.NodeTag) packet;
                    
                    int tagId = tag.id().id();
                    int nodeId = tag.node().id();
                    String tagStr = ((org.ucombinator.jaam.serializer.Tag)tag.tag()).toString();
                    Main.graph.addTag(nodeId,tagStr);
                }

                packet = packetInput.read();
			}
		}
		catch(FileNotFoundException e)
		{
			System.out.println(e);
		}
	}

	public static void readSmallDummyGraph() {
		int dummyInstructions = 6;
		for (int i = 0; i < dummyInstructions; i++) {
			if (i < 3) {
				Instruction inst = new Instruction("i" + Integer.toString(i) + " = " + Integer.toString(i),
						"Main.main", i, true);
				Main.graph.addVertex((i + 1), inst, true);
			} else {
				Instruction inst = new Instruction("i" + Integer.toString(i) + " = " + Integer.toString(i),
						"Main.func", i, true);
				Main.graph.addVertex((i + 1), inst, true);
			}
		}

		Main.graph.addEdge(0, 1);
		Main.graph.addEdge(1, 2);
		Main.graph.addEdge(2, 3);
		Main.graph.addEdge(3, 4);
		Main.graph.addEdge(4, 5);
	}

	public static void readLargeDummyGraph() {
		int dummyInstructions = 16;
		for(int i = 0; i < dummyInstructions; i++) {
			if(i < 5) {
				Instruction inst = new Instruction("i" + Integer.toString(i) + " = " + Integer.toString(i),
						"Main.main", i, true);
				Main.graph.addVertex((i + 1), inst, true);
			}
			else {
				Instruction inst = new Instruction("i" + Integer.toString(i) + " = " + Integer.toString(i),
						"Main.func", i, true);
				Main.graph.addVertex((i + 1), inst, true);
			}
		}

		// Main.main
		Main.graph.addEdge(0, 1);
		Main.graph.addEdge(1, 2);
		Main.graph.addEdge(1, 3);
		Main.graph.addEdge(2, 4);
		Main.graph.addEdge(3, 4);

		// Main.func
		Main.graph.addEdge(4, 5);
		Main.graph.addEdge(5, 6);
		Main.graph.addEdge(6, 7);
		Main.graph.addEdge(6, 8);
		Main.graph.addEdge(7, 9);
		Main.graph.addEdge(8, 9);
		Main.graph.addEdge(9, 10);
		Main.graph.addEdge(10, 11);
		Main.graph.addEdge(11, 12);
		Main.graph.addEdge(12,13);
		Main.graph.addEdge(13,14);
		Main.graph.addEdge(14,15);
	}

	/*public static void loadDecompiledCode()
	{
		if(Main.graph != null)
		{
			File file = Parameters.openFile(true);
			if(file.isDirectory())
			{
				ArrayList<File> javaFiles = getJavaFilesRec(file);
				Main.graph.matchClassesToCode(file.getAbsolutePath() + "/", javaFiles);
			}
			else if(file.getAbsolutePath().endsWith(".java"))
			{
				//For now, we assume that there is only one class, because otherwise the user
				//would load a directory.
				if(Main.graph.classes.size() == 1)
				{
					Class ourClass = Main.graph.classes.entrySet().iterator().next().getValue();
					ourClass.parseJavaFile(file.getAbsolutePath());
				}
				else
					System.out.println("Cannot load single class. Number of classes: " + Main.graph.classes.size());
			}
		}
		else
		{
			System.out.println("Cannot load source code until we have a graph...");
		}
	}*/

	public static ArrayList<File> getJavaFilesRec(File file)
	{
		ArrayList<File> javaFiles = new ArrayList<File>();
		Stack<File> toSearch = new Stack<File>();
		toSearch.add(file);

		while (!toSearch.isEmpty())
		{
			File nextFilepath = toSearch.pop();
			if (nextFilepath.isFile() && nextFilepath.toString().endsWith(".java"))
			{
				//Add this .java file
				javaFiles.add(nextFilepath);
			}
			else if (nextFilepath.isDirectory())
			{
				//Search directory for more .java files
				File[] newFilepaths = nextFilepath.listFiles();

				//Assume we actually have a tree of directories, with no extra links
				for (File f : newFilepaths)
					toSearch.add(f);
			}
		}

		return javaFiles;
	}
}
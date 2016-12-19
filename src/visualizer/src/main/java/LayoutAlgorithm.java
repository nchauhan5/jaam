import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;

import javafx.print.Collation;

public class LayoutAlgorithm {
	// This works on a graph whose vertices have been assigned a bounding box
	final static double MARGIN_PADDING = .25;
	final static double NODES_PADDING = .5;
	public static void defaultLayout(AbstractVertex parenteVertex, AbstractGraph graph){
		
		Iterator<AbstractVertex> it = graph.getVertices().values().iterator();
		while(it.hasNext()){
			AbstractVertex v = it.next();
			AbstractGraph inner_graph = v.getInnerGraph();
			if(inner_graph.getVertices().size()!=0){
				//Layout the inner graphs of each node and assign width and height to each node
				//coordinates are RELATIVE to the parent
				defaultLayout(v, inner_graph);
			}
		}
		//Layout the current graph
		defaultNonRecursiveLayout(parenteVertex, graph);
		
	}
	
	// We assign x,y to each vertex of graph
	// We assign width and height to the partentVertex 
	public static void defaultNonRecursiveLayout(AbstractVertex partentVertex, AbstractGraph graph){
	    Iterator<AbstractVertex> it = graph.getVertices().values().iterator();
		//Just initialize all the nodes to be white
		while(it.hasNext()){
			it.next().vertexStatus = "WHITE";
		}
		
		ArrayList<AbstractVertex> arrayList = new ArrayList<AbstractVertex>();
		arrayList.addAll(graph.getVertices().values());
		Collections.sort(arrayList);
		AbstractVertex root =arrayList.get(0);
		
		double[] xy = visit(root,MARGIN_PADDING,MARGIN_PADDING);
		partentVertex.setWidth(xy[0]+2*MARGIN_PADDING);
		partentVertex.setHeight(xy[1]+2*MARGIN_PADDING);
	}
	
	public static double[] visit(AbstractVertex root, double left, double top){
		
		root.vertexStatus = "GRAY";
		
		Iterator<AbstractVertex> it = root.getAsbstractNeighbors().iterator();
		ArrayList<AbstractVertex> grayChildren = new ArrayList<AbstractVertex>(); 
		while(it.hasNext()){
			AbstractVertex child = it.next();
			if (child.vertexStatus.equals("WHITE")){
				child.vertexStatus = "GRAY";
				grayChildren.add(child);
			}
		}
		
		double currentWidth = 0;
		double currentHeight = 0; 
		Iterator<AbstractVertex> itGray = grayChildren.iterator();
		while(itGray.hasNext()){		
			AbstractVertex curVer = itGray.next();
			double[] bbox = visit(curVer,currentWidth+left,NODES_PADDING+top+root.getHeight());
//			currentWidth+=bbox[0]+curVer.getWidth();
			currentWidth+=bbox[0]+NODES_PADDING;
			currentHeight=Math.max(currentHeight, bbox[1]);
		}
		
		root.subtreeBBOX[0]=Math.max(root.getWidth(),currentWidth-NODES_PADDING);
		if(grayChildren.size()==0){
			root.subtreeBBOX[1]=root.getHeight();
		}else{
			root.subtreeBBOX[1]=NODES_PADDING+root.getHeight()+currentHeight;
		}
		
		root.setX(left+((root.subtreeBBOX[0]-root.getWidth())/2));  //left-most corner x
		root.setY(top);							    //top-most corner y
		root.vertexStatus = "BLACK";		
		
		return root.subtreeBBOX;
	}
}
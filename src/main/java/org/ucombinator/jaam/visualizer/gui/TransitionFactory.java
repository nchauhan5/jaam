package org.ucombinator.jaam.visualizer.gui;

import javafx.animation.*;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.ucombinator.jaam.visualizer.layout.AbstractLayoutVertex;
import org.ucombinator.jaam.visualizer.layout.HierarchicalGraph;
import org.ucombinator.jaam.visualizer.layout.LayoutEdge;
import org.ucombinator.jaam.visualizer.layout.StateVertex;

public class TransitionFactory {

    private static Duration time = Duration.millis(1000);

    public static ParallelTransition buildRecursiveTransition(StateVertex v) {
        ParallelTransition pt = new ParallelTransition();
        HierarchicalGraph<StateVertex> innerGraph = v.getInnerGraph();

        // Add transitions for current node and the edges it contains.
        pt.getChildren().add(TransitionFactory.buildVertexTransition(v));
        for(LayoutEdge<StateVertex> e : innerGraph.getVisibleEdges()) {
            pt.getChildren().add(TransitionFactory.buildEdgeTransition(e));
        }

        // Recurse for its children in our graph hierarchy.
        for(StateVertex v2 : innerGraph.getVisibleVertices()) {
            pt.getChildren().add(TransitionFactory.buildRecursiveTransition(v2));
        }

        return pt;
    }

    public static ParallelTransition buildVertexTransition(StateVertex v) {
        System.out.println("Creating transition for vertex: " + v.toString());
        GUINodeStatus status = v.getNodeStatus();
        GUINode<StateVertex> node = v.getGraphics();
        Rectangle rect = node.getRect();

        FadeTransition ft = new FadeTransition(time);
        ft.setToValue(status.opacity);

        TranslateTransition tt = new TranslateTransition(time);
        tt.setToX(status.x);
        tt.setToY(status.y);

        Timeline widthTimeline = new Timeline(new KeyFrame(time, new KeyValue(rect.widthProperty(), status.width)));
        Timeline heightTimeline = new Timeline(new KeyFrame(time, new KeyValue(rect.heightProperty(), status.height)));

        return new ParallelTransition(node, ft, tt, widthTimeline, heightTimeline);
    }

    public static FadeTransition buildEdgeTransition(LayoutEdge<StateVertex> e) {
        System.out.println("Creating transition for edge: " + e.toString());
        FadeTransition ft = new FadeTransition(time, e.getGraphics());
        ft.setToValue(e.getOpacity());

        return ft;
    }
}

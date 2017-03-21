package org.ucombinator.jaam.visualizer.layout;

import java.util.HashSet;

/**
 * Created by timothyjohnson on 2/15/17.
 */
public class LayoutChainVertex extends AbstractLayoutVertex {

    public LayoutChainVertex(boolean drawEdges) {
        super("", VertexType.CHAIN, drawEdges);
    }

    public String getRightPanelContent() {
        return "Chain vertex, size = " + this.getInnerGraph().getVertices().size() + "\n";
    }

    public String getShortDescription() {
        return "Chain vertex, size = " + this.getInnerGraph().getVertices().size();
    }

    public boolean searchByMethod(String query) {
        boolean found = false;
        for(AbstractLayoutVertex v : this.getInnerGraph().getVertices().values()) {
            found = found || v.searchByMethod(query);
        }

        this.setHighlighted(found);
        return found;
    }

    public HashSet<LayoutMethodVertex> getMethodVertices()
    {
        HashSet<LayoutMethodVertex> methodVertices = new HashSet<LayoutMethodVertex>();
        for(AbstractLayoutVertex v : this.getInnerGraph().getVertices().values()) {
            if(v instanceof LayoutMethodVertex)
                methodVertices.add((LayoutMethodVertex) v);
            else
                methodVertices.addAll(v.getMethodVertices());
        }

        return methodVertices;
    }
}

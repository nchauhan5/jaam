package org.ucombinator.jaam.visualizer.layout;

import javafx.scene.paint.Color;
import org.ucombinator.jaam.visualizer.controllers.MainTabController;
import org.ucombinator.jaam.serializer.LoopMethodNode;

import java.util.LinkedHashSet;

public class LayoutMethodVertex extends StateVertex implements CodeEntity {

    private static final Color defaultColor = Color.DEEPSKYBLUE;

    private LoopMethodNode compilationUnit;

    public LayoutMethodVertex(int id, String label, LoopMethodNode compilationUnit){
    	super(id, label, VertexType.METHOD);
    	this.setDefaultColor();
    	this.compilationUnit = compilationUnit;
    }

    public String getClassName() {
        return this.compilationUnit.method().getDeclaringClass().getName();
    }

    public String getShortClassName() {
        return this.compilationUnit.method().getDeclaringClass().getShortName();
    }

    public String getMethodName() {
        return this.compilationUnit.method().getName();
    }

    public LoopMethodNode getCompilationUnit() {
        return compilationUnit;
    }

    public String getRightPanelContent() {
        return "Method vertex: " + this.getId();
    }

    @Override
    public boolean searchByMethod(String query, MainTabController mainTab) {
        boolean found = this.getMethodName().toLowerCase().contains(query);
        System.out.println(this.getMethodName() + " Method Checking:");
        if(found) {
            System.out.println("Found " + this);
            this.setHighlighted(found);
            mainTab.getHighlighted().add(this);
        }

        for(StateVertex v : this.getInnerGraph().getVisibleVertices()) {
            v.searchByMethod(query, mainTab);
        }

        return found;
    }

    public LinkedHashSet<LayoutMethodVertex> getMethodVertices() {
        LinkedHashSet<LayoutMethodVertex> result = new LinkedHashSet<LayoutMethodVertex>();
        result.add(this);
        return result;
    }

    public void setDefaultColor(){
        this.color = defaultColor;
    }

    @Override
    public String toString()
    {
        return "Method " + this.getClassName() + ":" + getMethodName();
    }
}

	import java.awt.Point;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Observable;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.event.EventType;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

public class AnimationHandler implements javafx.event.EventHandler<javafx.scene.input.MouseEvent>
{
	@Override
	public void handle(MouseEvent event) {
		EventType<MouseEvent> type = (EventType<MouseEvent>) event.getEventType();
		if(type.equals(MouseEvent.MOUSE_CLICKED))
		{
			if(event.getButton().equals(MouseButton.PRIMARY))
			{
				switch (event.getClickCount())
				{
					case 1:
						handlePrimarySingleClick(event);
						break;
					case 2:
						handlePrimaryDoubleClick(event);
						break;
					default:
						break;
				}
			}
			else if(event.getButton().equals(MouseButton.SECONDARY)) {}
			else if(event.getButton().equals(MouseButton.MIDDLE)) {}
		}
		else
		{
			System.out.println("This line should never be printed since we add the handler by setOnMouseClicked");
		}
	}

	private void handlePrimaryDoubleClickTim(MouseEvent event)
	{
		AbstractVertex v = ((GUINode)(event.getSource())).getVertex();
		if(v.isExpanded())
		{
			System.out.println("Collapsing node: " + v.id);
			// Move center up from half of current height to half of new height
			TranslateTransition tt = new TranslateTransition(Duration.millis(300), v.getGraphics());
			tt.setByX(Parameters.stFrame.mainPanel.scaleX((AbstractVertex.DEFAULT_WIDTH - v.getWidth()) / 2.0));
			tt.setByY(Parameters.stFrame.mainPanel.scaleY(-v.getHeight()/2.0 + AbstractVertex.DEFAULT_HEIGHT/2.0 - LayoutAlgorithm.MARGIN_PADDING));
			tt.play();

			// Scale to new height and width
			ScaleTransition st = new ScaleTransition(Duration.millis(300), v.getGraphics());
			st.setToX(AbstractVertex.DEFAULT_WIDTH / v.getWidth());
			st.setToY(AbstractVertex.DEFAULT_HEIGHT / v.getHeight());
			st.play();

			v.setExpanded(false);

			LayoutAlgorithm.layout(Parameters.stFrame.mainPanel.getPanelRoot());
			st.setOnFinished(new EventHandler<ActionEvent>() {
				@Override
				public void handle(ActionEvent event)
				{
					Iterator<AbstractVertex> itMethodVertices = Parameters.stFrame.mainPanel.getPanelRoot().getInnerGraph().getVertices().values().iterator();

//					while (itMethodVertices.hasNext())
//					{
//						AbstractVertex vertex = itMethodVertices.next();
//						animate(vertex);
//					}
					animate_recursive(Parameters.stFrame.mainPanel.getPanelRoot(),1,1);
				}
			});
		}
		else
		{
			// Expand vertex
			System.out.println("Expanding node: " + v.id);
			ScaleTransition st = new ScaleTransition(Duration.millis(300), v.getGraphics());
			st.setToX(AbstractVertex.DEFAULT_WIDTH);
			st.setToY(AbstractVertex.DEFAULT_HEIGHT);
			st.play();

			v.setExpanded(true);
			LayoutAlgorithm.layout(Parameters.stFrame.mainPanel.getPanelRoot());
			st.setOnFinished(new EventHandler<ActionEvent>()
			{
				@Override
				public void handle(ActionEvent event)
				{
					Iterator<AbstractVertex> itMethodVertices = Parameters.stFrame.mainPanel.getPanelRoot().getInnerGraph().getVertices().values().iterator();

					while (itMethodVertices.hasNext())
					{
						AbstractVertex vertex = itMethodVertices.next();
						animate(vertex);
					}
				}
			});
		}
		event.consume();
	}
	
	
	private void collapsing(MouseEvent event)
	{
		event.consume();
		AbstractVertex v = ((GUINode)(event.getSource())).getVertex();
		
		Iterator<Node> it = v.getGraphics().getChildren().iterator();
		while(it.hasNext())
		{
			Node n = it.next();
			if(!n.getClass().equals(Rectangle.class))
			{
				FadeTransition ft = new FadeTransition(Duration.millis(300), n);
				ft.setToValue(0.0);
				
				ft.setOnFinished(new EventHandler<ActionEvent>() {
					@Override
					public void handle(ActionEvent event) {		
						n.setVisible(false);
					}
				});
				
				ft.play();
			}
		}
		

		
		/*****************************************************/
		v.setExpanded(false);
		System.out.println("Coordinated of the double-clicked vertex:");
		double oldWidth = v.getGraphics().getWidth();
		double oldHeight = v.getGraphics().getHeight();
		LayoutAlgorithm.layout(Parameters.stFrame.mainPanel.getPanelRoot());
		/*****************************************************/
		
	
				animate_recursive(Parameters.stFrame.mainPanel.getPanelRoot(),1,1);
		
		

	}
	
//	private void bla(MouseEvent event)
//	{
//		event.consume();
//		AbstractVertex v = ((GUINode)(event.getSource())).getVertex();
//		
//		Iterator<Node> it = v.getGraphics().getChildren().iterator();
//		while(it.hasNext())
//		{
//			Node n = it.next();
//			if(!n.getClass().equals(Rectangle.class))
//			{
//				FadeTransition ft = new FadeTransition(Duration.millis(300), n);
//				ft.setToValue(1);
//				
//				n.setVisible(true);				
//				ft.play();
//			}
//		}
//		
//
//		
//		/*****************************************************/
//		v.setExpanded(true);
//		System.out.println("Coordinated of the double-clicked vertex:");
//		double oldWidth = v.getGraphics().getWidth();
//		double oldHeight = v.getGraphics().getHeight();
//		LayoutAlgorithm.layout(Parameters.stFrame.mainPanel.getPanelRoot());
//		/*****************************************************/
//		
//	
//				//animate_recursive(Parameters.stFrame.mainPanel.getPanelRoot(),1,1);
//
//	}
	
	
	private void expanding(MouseEvent event)
	{
		AbstractVertex v = ((GUINode)(event.getSource())).getVertex();
		
		Iterator<Node> it = v.getGraphics().getChildren().iterator();
		while(it.hasNext())
		{
			Node n = it.next();
			if(!n.getClass().equals(Rectangle.class))
			{
				FadeTransition ft = new FadeTransition(Duration.millis(300), n);
				ft.setToValue(1);
				
				ft.setOnFinished(new EventHandler<ActionEvent>() {
					@Override
					public void handle(ActionEvent event) {		
						n.setVisible(true);
					}
				});
				
				ft.play();
				event.consume();
			}
		}
		
		
		
					System.out.println("Expanding node: " + v.id);
					ScaleTransition st = new ScaleTransition(Duration.millis(300), v.getGraphics());
					st.setToX(AbstractVertex.DEFAULT_WIDTH);
					st.setToY(AbstractVertex.DEFAULT_HEIGHT);
					st.play();

					v.setExpanded(true);
					LayoutAlgorithm.layout(Parameters.stFrame.mainPanel.getPanelRoot());
					st.setOnFinished(new EventHandler<ActionEvent>()
					{
						@Override
						public void handle(ActionEvent event)
						{
							Iterator<AbstractVertex> itMethodVertices = Parameters.stFrame.mainPanel.getPanelRoot().getInnerGraph().getVertices().values().iterator();

							while (itMethodVertices.hasNext())
							{
								AbstractVertex vertex = itMethodVertices.next();
								animate(vertex);
							}
						}
					});
					


	}
	
	private void handlePrimaryDoubleClick(MouseEvent event)
	{
		if((((GUINode)(event.getSource())).getVertex()).isExpanded()){
			collapsing(event);
		}else{
			expanding(event);
		}
				
		event.consume();
	}

	
	private void savePositions(AbstractVertex v, HashMap<String, Point2D> oldPositions){
		
		
		oldPositions.put(v.getStrID(), 
				new Point2D(Parameters.stFrame.mainPanel.scaleX(v.getX()), 
							Parameters.stFrame.mainPanel.scaleY(v.getY())));
		
		Iterator<AbstractVertex> it = v.getInnerGraph().getVertices().values().iterator();
		while (it.hasNext())
		{
			savePositions(it.next(),oldPositions);
		}
	}

	
	private void animate_recursive(AbstractVertex v, double parentScaleX, double parentScaleY) {
		
		
		
		double oldWidth = v.getGraphics().getWidth();
		//double currentWidth = v.getGraphics().getScaleX()*oldWidth;
		double oldHeight = v.getGraphics().getHeight();
		//double currentHeight = v.getGraphics().getScaleY()*oldHeight;

		double pixelWidth = Parameters.stFrame.mainPanel.scaleX(v.getWidth());
		double pixelHeight = Parameters.stFrame.mainPanel.scaleY(v.getHeight());
		
//		double previousWidth = v.getWidth()*v.getGraphics().getScaleX();
//		double previousHeight = v.getHeight()*v.getGraphics().getScaleY();
		
			ScaleTransition st = new ScaleTransition(Duration.millis(500), v.getGraphics());
			st.setFromX(v.getGraphics().getScaleX());
			st.setFromY(v.getGraphics().getScaleY());
			st.setToX(pixelWidth/(parentScaleX*oldWidth));
			st.setToY(pixelHeight/(parentScaleY*oldHeight));
			
			
			st.setOnFinished(new EventHandler<ActionEvent>() {
				@Override
				public void handle(ActionEvent event)
				{
					event.consume();	
//					double previousWidth = v.getWidth()*v.getGraphics().getScaleX();
//					double previousHeight = v.getHeight()*v.getGraphics().getScaleY();
					double pixelWidth = Parameters.stFrame.mainPanel.scaleX(v.getWidth());
					double pixelHeight = Parameters.stFrame.mainPanel.scaleY(v.getHeight());
//					v.getGraphics().setLayoutX((oldWidth-pixelWidth)/2);
//					v.getGraphics().setLayoutY((oldHeight-pixelHeight)/2);
//					v.getGraphics().rect.setWidth(pixelWidth);
//					v.getGraphics().rect.setHeight(pixelHeight);
//					v.getGraphics().setScaleX(1);
//					v.getGraphics().setScaleY(1);
					
					double newWidth = v.getGraphics().getWidth()*v.getGraphics().getScaleX();
					double newHeight = v.getGraphics().getHeight()*v.getGraphics().getScaleY();
					
//					tt.setFromX(v.getGraphics().getTranslateX());
//					tt.setFromY(v.getGraphics().getTranslateY());
//					double AX = (newWidth-pixelWidth)/2.0;
//					double AY = (newHeight-pixelHeight)/2.0;

					
					v.getGraphics().rect.setWidth(pixelWidth);
					v.getGraphics().rect.setHeight(pixelHeight);
					
					v.getGraphics().setScaleX(1);
					v.getGraphics().setScaleY(1);
					
					
//					TranslateTransition tt = new TranslateTransition(Duration.millis(300), v.getGraphics());
//					tt.setFromX(v.getGraphics().getTranslateX());
//					tt.setFromY(v.getGraphics().getTranslateY());
//					tt.setToX(Parameters.stFrame.mainPanel.scaleX(v.getX()));
//					tt.setToY(Parameters.stFrame.mainPanel.scaleY(v.getY()));
					v.getGraphics().setTranslateX(Parameters.stFrame.mainPanel.scaleX(v.getX()));
					v.getGraphics().setTranslateY(Parameters.stFrame.mainPanel.scaleY(v.getY()));
					
					
//					tt.setOnFinished(new EventHandler<ActionEvent>() {
//						@Override
//						public void handle(ActionEvent event)
//						{
//							event.consume();
//							v.getGraphics().setTranslateX(Parameters.stFrame.mainPanel.scaleX(v.getX()));
//							v.getGraphics().setTranslateY(Parameters.stFrame.mainPanel.scaleY(v.getY()));			
//							v.getGraphics().setLayoutX(0);
//							v.getGraphics().setLayoutY(0);
//						}
//							
//					});
					
//					tt.play();
					
					

				}
			});
			
			
			Iterator<AbstractVertex> it = v.getInnerGraph().getVertices().values().iterator();
			while(it.hasNext()){
				AbstractVertex next = it.next();
				if(v.isExpanded()){
//					animate_recursive(next, parentScaleX*v.getGraphics().getScaleX(), parentScaleY*v.getGraphics().getScaleY());
					animate_recursive(next, pixelWidth/oldWidth, pixelHeight/oldHeight);
				}
			}
			
			
		    st.play();
		
			
			
		
	}
	public void animate(AbstractVertex v)
	{
		
//		v.getGraphics().setLayoutX(Parameters.stFrame.mainPanel.scaleX(v.getX()));
//		v.getGraphics().setLayoutY(Parameters.stFrame.mainPanel.scaleY(v.getY()));
		TranslateTransition tt = new TranslateTransition(Duration.millis(300), v.getGraphics());
		double oldWidth = v.getGraphics().getWidth();
		double currentWidth = v.getGraphics().getScaleX()*oldWidth;
		double oldHeight = v.getGraphics().getHeight();
		double currentHeight = v.getGraphics().getScaleY()*oldHeight;
		
//		tt.setToX(Parameters.stFrame.mainPanel.scaleX(v.getX()) - (oldWidth-currentWidth)/2);
//		tt.setToY(Parameters.stFrame.mainPanel.scaleY(v.getY()) - (oldHeight-currentHeight)/2);
		tt.setToX(Parameters.stFrame.mainPanel.scaleX(v.getX()) - (oldWidth-currentWidth)/2);
		tt.setToY(Parameters.stFrame.mainPanel.scaleY(v.getY()) - (oldHeight-currentHeight)/2);
		tt.play();

	}

	private void handlePrimarySingleClick(MouseEvent event)
	{
		AbstractVertex v = ((GUINode)(event.getSource())).getVertex();
		System.out.println("Single click: " + v.getStrID());
		event.consume();
	}
}

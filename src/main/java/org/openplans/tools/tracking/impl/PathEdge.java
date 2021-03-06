package org.openplans.tools.tracking.impl;

import gov.sandia.cognition.math.matrix.Matrix;
import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian;

import org.openplans.tools.tracking.impl.InferredGraph.InferredEdge;

import com.google.common.base.Preconditions;

public class PathEdge {

  private final InferredEdge edge;
  private final Double distToStartOfEdge;
  
  private PathEdge(InferredEdge edge) {
    this.edge = edge;
    this.distToStartOfEdge = null;
  }
  
  private PathEdge(InferredEdge edge, double distToStartOfEdge) {
    Preconditions.checkArgument(edge != InferredGraph.getEmptyEdge());
    this.edge = edge;
    this.distToStartOfEdge = distToStartOfEdge;
  }

  public InferredEdge getInferredEdge() {
    return edge;
  }

  public Double getDistToStartOfEdge() {
    return distToStartOfEdge;
  }

  @Override
  public String toString() {
    return "PathEdge [edge=" + edge + ", distToStartOfEdge="
        + distToStartOfEdge + "]";
  }

  public static PathEdge getEdge(InferredEdge infEdge) {
    PathEdge edge;
    if (infEdge == InferredGraph.getEmptyEdge()) {
      edge = PathEdge.getEmptyPathEdge(); 
    } else {
      edge = new PathEdge(infEdge, 0d);
    }
    return edge;
  }
  
  public static PathEdge getEdge(InferredEdge infEdge, double distToStart) {
    PathEdge edge;
    if (infEdge == InferredGraph.getEmptyEdge()) {
      edge = PathEdge.getEmptyPathEdge(); 
    } else {
      edge = new PathEdge(infEdge, distToStart);
    }
    return edge;
  }
  
  private static PathEdge emptyPathEdge = new PathEdge(InferredGraph.getEmptyEdge());

  public static PathEdge getEmptyPathEdge() {
    return emptyPathEdge;
  }
  
  /**
   * This method truncates the given belief over the interval
   * defined by this edge. 
   * @param belief
   */
  public void predict(MultivariateGaussian belief) {
    /*-
     * TODO really, this should just be the truncated/conditional
     * mean and covariance for the given interval/edge
     */
    final Matrix Or = StandardRoadTrackingFilter.getOr();
    final double S = Or.times(belief.getCovariance()).times(Or.transpose()).getElement(0, 0) 
        + Math.pow(edge.getLength()/Math.sqrt(12), 2);
    final Matrix W = belief.getCovariance().times(Or.transpose()).scale(1/S);
    final Matrix R = belief.getCovariance().minus(W.times(W.transpose()).scale(S));
    final double mean = (distToStartOfEdge + edge.getLength())/2d;
    final double e = mean - Or.times(belief.getMean()).getElement(0);
    final Vector a = belief.getMean().plus(W.getColumn(0).scale(e));
    
    belief.setMean(a);
    belief.setCovariance(R);
  }
}

package org.openplans.tools.tracking.impl;

import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.math.matrix.VectorFactory;
import gov.sandia.cognition.statistics.ComputableDistribution;
import gov.sandia.cognition.statistics.ProbabilityFunction;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian;

import java.util.ArrayList;
import java.util.Random;

import jj2000.j2k.NotImplementedError;

import org.openplans.tools.tracking.impl.InferredGraph.InferredEdge;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

/**
 * This class represents the state of a vehicle, which is made up of the
 * vehicles location, whether it is on an edge, which path it took from its
 * previous location on an edge, and the distributions that determine these.
 * 
 * @author bwillard
 * 
 */
public class VehicleState implements
    ComputableDistribution<VehicleStateConditionalParams> {

  public static class InitialParameters {
    private final Vector obsVariance;
    private final Vector onRoadStateVariance;
    private final Vector offRoadStateVariance;
    private final Vector offTransitionProbs;
    private final Vector onTransitionProbs;
    private final long seed;

    public InitialParameters(Vector obsVariance, Vector onRoadStateVariance,
      Vector offRoadStateVariance, Vector offProbs, Vector onProbs, long seed) {
      this.obsVariance = obsVariance;
      this.onRoadStateVariance = onRoadStateVariance;
      this.offRoadStateVariance = offRoadStateVariance;
      this.offTransitionProbs = offProbs;
      this.onTransitionProbs = onProbs;
      this.seed = seed;
    }

    public Vector getObsVariance() {
      return obsVariance;
    }

    public Vector getOffRoadStateVariance() {
      return offRoadStateVariance;
    }

    public Vector getOffTransitionProbs() {
      return offTransitionProbs;
    }

    public Vector getOnRoadStateVariance() {
      return onRoadStateVariance;
    }

    public Vector getOnTransitionProbs() {
      return onTransitionProbs;
    }

    public long getSeed() {
      return seed;
    }
  }

  public static class PDF extends VehicleState implements
      ProbabilityFunction<VehicleStateConditionalParams> {

    private static final long serialVersionUID = 879217079360170446L;

    public PDF(VehicleState state) {
      super(state);
    }

    @Override
    public PDF clone() {
      return (PDF) super.clone();
    }

    @Override
    public Double evaluate(VehicleStateConditionalParams input) {
      return Math.exp(logEvaluate(input));
    }

    @Override
    public VehicleState.PDF getProbabilityFunction() {
      return this;
    }

    @Override
    public double logEvaluate(VehicleStateConditionalParams input) {
      double logLikelihood = 0d;

      final InferredEdge previousEdge = input.getPathEdge().getInferredEdge();
      /*
       * Edge transitions
       */
      logLikelihood += this.edgeTransitionDist.logEvaluate(previousEdge,
          this.getInferredEdge());

      /*
       * Movement likelihood Note: should be predictive for PL
       */
      PathEdge edge;
      if (this.getInferredEdge() == InferredGraph.getEmptyEdge()) {
        edge = PathEdge.getEmptyPathEdge();
      } else {
        edge = PathEdge.getEdge(this.getInferredEdge(),
            input.getDistanceToCurrentEdge());
      }
      logLikelihood += this.getMovementFilter().logLikelihood(
          input.getLocation(), this.belief, edge);

      return logLikelihood;
    }

    @Override
    public VehicleStateConditionalParams sample(Random random) {
      throw new NotImplementedError();
    }

    @Override
    public ArrayList<VehicleStateConditionalParams> sample(Random random,
      int numSamples) {
      throw new NotImplementedError();
    }

  }

  private static final long serialVersionUID = 3229140254421801273L;

  private static final double gVariance = 50d * 50d / 4d; // meters
  private static final double dVariance = Math.pow(0.05, 2) / 4d; // m/s^2

  // TODO FIXME pretty sure this constant is being used in multiple places
  // for different things...
  private static final double vVariance = Math.pow(0.05, 2) / 4d; // m/s^2

  /*
   * These members represent the state/parameter samples/sufficient statistics.
   */
  private final StandardRoadTrackingFilter movementFilter;

  /**
   * This could be the 4D ground-coordinates dist. for free motion, or the 2D
   * road-coordinates, either way the tracking filter will check.
   */
  protected final MultivariateGaussian belief;

  /*-
   * Edge transition priors 
   * 1. edge off 
   * 2. edge on 
   * 3. edges transitions to others (one for all)
   * edges
   */
  protected final EdgeTransitionDistributions edgeTransitionDist;
  private final Observation observation;
  private final InferredEdge edge;
  private VehicleState parentState = null;
  private final Double distanceFromPreviousState;
  private final InferredPath path;

  private final InferredGraph graph;

  public VehicleState(InferredGraph graph, Observation initialObservation,
    InferredEdge inferredEdge, InitialParameters parameters) {
    Preconditions.checkNotNull(initialObservation);
    Preconditions.checkNotNull(inferredEdge);

    this.movementFilter = new StandardRoadTrackingFilter(
        parameters.getObsVariance(), parameters.getOffRoadStateVariance(),
        parameters.getOnRoadStateVariance());

    final double timeDiff;
    if (initialObservation.getPreviousObservation() != null) {
      timeDiff = (initialObservation.getTimestamp().getTime() - initialObservation
          .getPreviousObservation().getTimestamp().getTime()) / 1000d;
    } else {
      timeDiff = 30d;
    }
    Preconditions.checkArgument(timeDiff > 0d);
    this.movementFilter.setCurrentTimeDiff(timeDiff);

    if (inferredEdge == InferredGraph.getEmptyEdge()) {
      this.belief = movementFilter.getGroundFilter()
          .createInitialLearnedObject();
      final Vector xyPoint = initialObservation.getProjectedPoint();
      belief.setMean(VectorFactory.getDefault()
          .copyArray(
              new double[] { xyPoint.getElement(0), 0d, xyPoint.getElement(1),
                  0d }));

      this.path = InferredPath.getEmptyPath();
    } else {
      /*
       * Find our starting position on this edge
       */
      this.belief = movementFilter.getRoadFilter().createInitialLearnedObject();
      final Vector loc = inferredEdge.getPointOnEdge(initialObservation
          .getObsCoords());
      belief.setMean(VectorFactory.getDefault().copyArray(
          new double[] { inferredEdge.getStartPoint().euclideanDistance(loc),
              0d }));

      this.path = new InferredPath(inferredEdge);
    }

    this.edge = inferredEdge;
    this.observation = initialObservation;
    this.graph = graph;
    this.edgeTransitionDist = new EdgeTransitionDistributions(this.graph,
        parameters.getOnTransitionProbs(), parameters.getOffTransitionProbs());
    this.distanceFromPreviousState = 0d;
  }

  public VehicleState(InferredGraph graph, Observation observation,
    StandardRoadTrackingFilter filter, MultivariateGaussian belief,
    EdgeTransitionDistributions edgeTransitionDist, PathEdge edge,
    InferredPath path, VehicleState state) {
    this.observation = observation;
    this.movementFilter = filter;
    this.belief = belief;
    this.graph = graph;
    /*
     * This is the constructor used when creating transition states, so this is
     * where we'll need to reset the distance measures
     */
    this.distanceFromPreviousState = edge.getDistToStartOfEdge();
    if (belief.getInputDimensionality() == 2)
      belief.getMean().setElement(0,
          belief.getMean().getElement(0) - edge.getDistToStartOfEdge());
    this.edgeTransitionDist = edgeTransitionDist;
    this.edge = edge.getInferredEdge();
    this.parentState = state;
    this.path = path;
    final double timeDiff;
    if (observation.getPreviousObservation() != null) {
      timeDiff = (observation.getTimestamp().getTime() - observation
          .getPreviousObservation().getTimestamp().getTime()) / 1000d;
    } else {
      timeDiff = 30d;
    }
    this.movementFilter.setCurrentTimeDiff(timeDiff);

  }

  public VehicleState(VehicleState other) {
    this.graph = other.graph;
    this.movementFilter = other.movementFilter;
    this.belief = other.belief;
    this.edgeTransitionDist = other.edgeTransitionDist;
    this.edge = other.edge;
    this.observation = other.observation;
    this.distanceFromPreviousState = other.distanceFromPreviousState;
    this.parentState = other.parentState;
    this.path = other.path;
  }

  @Override
  public VehicleState clone() {
    try {
      return (VehicleState) super.clone();
    } catch (final CloneNotSupportedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    return null;
  }

  public MultivariateGaussian getBelief() {
    return belief;
  }

  public Double getDistanceFromPreviousState() {
    return distanceFromPreviousState;
  }

  public InferredEdge getEdge() {
    return edge;
  }

  public EdgeTransitionDistributions getEdgeTransitionDist() {
    return edgeTransitionDist;
  }

  public InferredGraph getGraph() {
    return graph;
  }

  /**
   * Gets the belief in ground coordinates (even if it's really tracking road
   * coordinates).
   * 
   * @return
   */
  public MultivariateGaussian getGroundOnlyBelief() {
    if (belief.getInputDimensionality() == 2) {
      final MultivariateGaussian beliefProj = new MultivariateGaussian();
      StandardRoadTrackingFilter.convertToGroundBelief(beliefProj,
          PathEdge.getEdge(this.edge, 0d));
      return beliefProj;
    } else {
      return belief;
    }
  }

  public InferredEdge getInferredEdge() {
    return this.edge;
  }

  /**
   * Returns ground-coordinate mean location
   * 
   * @return
   */
  public Vector getMeanLocation() {
    Vector v;
    if (belief.getInputDimensionality() == 2) {
      Preconditions.checkArgument(this.edge != InferredGraph.getEmptyEdge());
      final MultivariateGaussian projBelief = belief.clone();
      StandardRoadTrackingFilter.invertProjection(projBelief,
          PathEdge.getEdge(this.edge, 0d));
      v = projBelief.getMean();
    } else {
      v = belief.getMean();
    }
    return VectorFactory.getDefault().createVector2D(v.getElement(0),
        v.getElement(2));
  }

  public StandardRoadTrackingFilter getMovementFilter() {
    return movementFilter;
  }

  public Observation getObservation() {
    return observation;
  }

  public VehicleState getParentState() {
    return parentState;
  }

  public InferredPath getPath() {
    return path;
  }

  @Override
  public VehicleState.PDF getProbabilityFunction() {
    return new VehicleState.PDF(this);
  }

  @Override
  public VehicleStateConditionalParams sample(Random random) {
    throw new NotImplementedError();
  }

  @Override
  public ArrayList<VehicleStateConditionalParams> sample(Random random,
    int numSamples) {
    throw new NotImplementedError();
  }

  public void setParentState(VehicleState parentState) {
    this.parentState = parentState;
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this).add("belief", belief).add("edge", edge)
        .addValue(observation.getTimestamp()).add("parent", parentState)
        .add("filter", movementFilter).toString();
  }

  public static double getDvariance() {
    return dVariance;
  }

  public static double getGvariance() {
    return gVariance;
  }

  public static long getSerialversionuid() {
    return serialVersionUID;
  }

  public static double getVvariance() {
    return vVariance;
  }

}

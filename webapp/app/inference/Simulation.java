package inference;

import gov.sandia.cognition.math.matrix.Matrix;
import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.math.matrix.VectorFactory;
import gov.sandia.cognition.math.matrix.mtj.DenseMatrixFactoryMTJ;
import gov.sandia.cognition.math.matrix.mtj.decomposition.CholeskyDecompositionMTJ;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian;

import java.util.Date;
import java.util.List;
import java.util.Random;

import models.InferenceInstance;

import org.openplans.tools.tracking.impl.EdgeTransitionDistributions;
import org.openplans.tools.tracking.impl.InferredGraph;
import org.openplans.tools.tracking.impl.InferredGraph.InferredEdge;
import org.openplans.tools.tracking.impl.InferredPath;
import org.openplans.tools.tracking.impl.Observation;
import org.openplans.tools.tracking.impl.PathEdge;
import org.openplans.tools.tracking.impl.StandardRoadTrackingFilter;
import org.openplans.tools.tracking.impl.TimeOrderException;
import org.openplans.tools.tracking.impl.VehicleState;
import org.openplans.tools.tracking.impl.VehicleState.InitialParameters;
import org.openplans.tools.tracking.impl.util.GeoUtils;

import play.Logger;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;

import akka.actor.UntypedActor;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.vividsolutions.jts.geom.Coordinate;

import controllers.Api;

public class Simulation {

//  private static final Logger _log = LoggerFactory.getLogger(Simulation.class);

  public static class SimulationActor extends UntypedActor {
    @Override
    public void onReceive(Object arg0) throws Exception {
      // TODO this is a lame way to get concurrency. fix this
      final Simulation sim = (Simulation) arg0;
      
      sim.runSimulation();
    }
  }

  private final long seed;
  private final Random rng;
  private final InferredGraph inferredGraph;
  private final String simulationName;
  private final InitialParameters parameters;
  private InferenceInstance instance;

  public static class SimulationParameters {
    
    private final Coordinate startCoordinate;
    private final Date startTime;
    private final Date endTime;
    private final long duration;
    private final long frequency;
    private final boolean performInference;
    
    public SimulationParameters(Coordinate startCoordinate, Date startTime, long duration, 
      long frequency, boolean performInference) {
      this.performInference = performInference;
      this.frequency = frequency;
      this.startCoordinate = startCoordinate;
      this.startTime = startTime;
      this.endTime = new Date(startTime.getTime() + duration * 1000);
      this.duration = duration;
    }

    public Coordinate getStartCoordinate() {
      return startCoordinate;
    }

    public Date getStartTime() {
      return startTime;
    }

    public Date getEndTime() {
      return endTime;
    }

    public long getDuration() {
      return duration;
    }

    public long getFrequency() {
      return frequency;
    }

    public boolean isPerformInference() {
      return performInference;
    }
  }
  
  private final SimulationParameters simParameters;
  
  public Simulation(String simulationName, InitialParameters parameters, 
    SimulationParameters simParameters) {

    this.simParameters = simParameters;
    this.parameters = parameters;
    
    this.inferredGraph = new InferredGraph(Api.getGraph());
    this.simulationName = simulationName;
    
    this.rng = new Random();
    if (parameters.getSeed() != 0l) {
      this.seed = parameters.getSeed();
    } else {
      this.seed = rng.nextLong();
    }
    this.rng.setSeed(seed);
    
    this.instance = InferenceService.getInferenceInstance(simulationName, true);
    this.instance.simSeed = seed;
    this.instance.totalRecords = (int)((simParameters.getEndTime().getTime() 
        - simParameters.getStartTime().getTime()) / (simParameters.getFrequency() * 1000d));
  }

  public String getSimulationName() {
    return simulationName;
  }

  public void runSimulation() {

    Logger.info("starting simulation with seed = " + seed);
    Observation initialObs;
    try {
      try {
        initialObs = Observation.createObservation(this.simulationName,
            this.simParameters.getStartTime(), 
            this.simParameters.getStartCoordinate(), null, null, null);
      } catch (final TimeOrderException e) {
        e.printStackTrace();
        return;
      }

      inferredGraph.getNearbyEdges(initialObs.getProjectedPoint());
      final List<InferredEdge> edges = Lists.newArrayList(InferredGraph
          .getEmptyEdge());
      final InferredEdge currentInferredEdge = edges.get(rng.nextInt(edges
          .size()));

      VehicleState vehicleState = new VehicleState(this.inferredGraph,
          initialObs, currentInferredEdge, parameters);

      long time = this.simParameters.getStartTime().getTime();
      int i = 0;
      while (time < this.simParameters.getEndTime().getTime()) {
        time += this.simParameters.getFrequency() * 1000;
        vehicleState = sampleState(vehicleState, time);
        i++;
      }

    } catch (final NumberFormatException e) {
      e.printStackTrace();
    }

  }

  /**
   * Use this sampling method to beat the inherent degeneracy of our state
   * covariance.
   * 
   * @param vehicleState
   * @return
   */
  public Vector sampleMovementBelief(Vector mean,
    StandardRoadTrackingFilter filter) {
    final boolean isRoad = mean.getDimensionality() == 2;
    final Matrix Q = isRoad ? filter.getQr() : filter.getQg();

    final Matrix covSqrt = CholeskyDecompositionMTJ.create(
        DenseMatrixFactoryMTJ.INSTANCE.copyMatrix(Q)).getR();
    Vector underlyingSample = MultivariateGaussian.sample(VectorFactory
        .getDefault().createVector(2), covSqrt, rng);
    final Matrix Gamma = filter.getCovarianceFactor(isRoad);
    Vector thisStateSample = Gamma.times(underlyingSample).plus(mean);
    return thisStateSample;
  }

  public Vector sampleObservation(VehicleState vehicleState, PathEdge edge) {

    final MultivariateGaussian gbelief = vehicleState.getBelief().clone();
    StandardRoadTrackingFilter.convertToGroundBelief(gbelief, edge);
    final Vector gMean = StandardRoadTrackingFilter.getOg().times(
        gbelief.getMean());
    final Matrix gCov = vehicleState.getMovementFilter().getGroundFilter()
        .getMeasurementCovariance();
    final Matrix covSqrt = CholeskyDecompositionMTJ.create(
        DenseMatrixFactoryMTJ.INSTANCE.copyMatrix(gCov)).getR();
    final Vector thisStateSample = MultivariateGaussian.sample(gMean, covSqrt,
        rng);
    return thisStateSample;
  }

  private VehicleState sampleState(VehicleState vehicleState, long time) {
    
    vehicleState.getMovementFilter().setCurrentTimeDiff(this.simParameters.getFrequency());
    final MultivariateGaussian previousLocBelief = vehicleState.getBelief().clone();
    final MultivariateGaussian currentLocBelief = vehicleState.getBelief();
    final EdgeTransitionDistributions currentEdgeTrans = vehicleState
        .getEdgeTransitionDist();
    final PathEdge currentPathEdge = PathEdge.getEdge(vehicleState
        .getInferredEdge());

    /*
     * Run through the edges, predict movement and reset the belief.
     */
    final InferredPath newPath = traverseEdge(
        vehicleState.getEdgeTransitionDist(), currentLocBelief,
        currentPathEdge, vehicleState.getMovementFilter());

    final PathEdge newPathEdge = Iterables.getLast(newPath.getEdges());

    /*
     * Sample from the state and observation noise
     */
    final Vector thisLoc = sampleObservation(vehicleState, newPathEdge);
    final Coordinate obsCoord = GeoUtils.convertToLatLon(thisLoc);
    Observation thisObs;
    try {
      thisObs = Observation.createObservation(simulationName, new Date(time),
          obsCoord, null, null, null);
    } catch (final TimeOrderException e) {
      e.printStackTrace();
      return null;
    }

    final VehicleState newState = new VehicleState(this.inferredGraph, thisObs,
        vehicleState.getMovementFilter(), currentLocBelief, currentEdgeTrans,
        newPathEdge, newPath, vehicleState);
    
    
    Logger.info("processed simulation observation :" + thisObs);
    
    if (this.simParameters.isPerformInference()) {
      instance.update(thisObs);
      
      Logger.info("processed simulation inference :" + thisObs);
    }
    
    
    final InferenceResultRecord result = InferenceResultRecord
        .createInferenceResultRecord(thisObs, newState, instance.getBestState());

    InferenceService.addSimulationRecords(simulationName, result);
    
    return newState;
  }

  /**
   * This method samples a path, updates the belief to reflect that, and returns
   * the path.
   * 
   * @param edgeTransDist
   * @param belief
   * @param startEdge
   * @param movementFilter
   * @return
   */
  private InferredPath traverseEdge(EdgeTransitionDistributions edgeTransDist,
    final MultivariateGaussian belief, PathEdge startEdge,
    StandardRoadTrackingFilter movementFilter) {

    /*
     * We project the road path
     */
    PathEdge currentEdge = startEdge;

    final List<PathEdge> currentPath = Lists.newArrayList();

    double distTraveled = 0d;
    Double totalDistToTravel = null;
    while (totalDistToTravel == null ||
    // the following case is when we're truly on the edge
        Math.abs(totalDistToTravel) >= Math.abs(currentEdge
            .getDistToStartOfEdge())
            + currentEdge.getInferredEdge().getLength()) {

      final List<InferredEdge> transferEdges = Lists.newArrayList();
      if (currentEdge.getInferredEdge() == InferredGraph.getEmptyEdge()) {
        final Vector projLocation = StandardRoadTrackingFilter.getOg().times(
            belief.getMean());
        transferEdges.addAll(this.inferredGraph.getNearbyEdges(projLocation));
      } else {
        if (totalDistToTravel == null) {
          transferEdges.add(currentEdge.getInferredEdge());
        } else if (belief.getMean().getElement(0) < 0d) {
          transferEdges.addAll(currentEdge.getInferredEdge()
              .getIncomingTransferableEdges());
        } else if (belief.getMean().getElement(0) > 0d) {
          transferEdges.addAll(currentEdge.getInferredEdge()
              .getOutgoingTransferableEdges());
        } else {
          transferEdges.addAll(currentEdge.getInferredEdge()
              .getIncomingTransferableEdges());
          transferEdges.addAll(currentEdge.getInferredEdge()
              .getOutgoingTransferableEdges());
        }
      }

      final InferredEdge sampledEdge = edgeTransDist.sample(rng, transferEdges,
          currentEdge.getInferredEdge());

      if (sampledEdge == InferredGraph.getEmptyEdge()) {

        /*
         * Off-road, so just return/add the empty path and be done
         */
        movementFilter
            .predict(belief, PathEdge.getEmptyPathEdge(), currentEdge);

        if (currentPath.isEmpty()) {
          return InferredPath.getEmptyPath();
        } else {
          currentPath.add(PathEdge.getEmptyPathEdge());
          return new InferredPath(ImmutableList.copyOf(currentPath),
              distTraveled);
        }
      }

      final PathEdge sampledPathEdge = PathEdge.getEdge(sampledEdge,
          distTraveled);

      if (totalDistToTravel == null) {
        /*
         * Predict the movement, i.e. distance and direction to travel. The mean
         * of this belief should be set to the true value, so the prediction is
         * exact.
         */
        if (belief.getInputDimensionality() == 4) {
          StandardRoadTrackingFilter.invertProjection(belief, sampledPathEdge);
        }

        final double currentLoc = belief.getMean().getElement(0);
        movementFilter.predict(belief, sampledPathEdge, null);
        /*
         * Adjust by the current location and sample
         */
        belief.getMean().setElement(0,
            belief.getMean().getElement(0) + currentLoc);

        final Vector transStateSample = sampleMovementBelief(belief.getMean(), 
            movementFilter);
        belief.setMean(transStateSample);
        
        totalDistToTravel = belief.getMean().getElement(0);
      }

      final double direction = belief.getMean().getElement(0) >= 0d ? 1d : -1d;

      if (sampledPathEdge == null) {
        /*-
         * We have nowhere else to go, but we're not moving off of an edge, so 
         * we call this a stop.
         */
        belief.getMean().setElement(0,
            direction * currentEdge.getInferredEdge().getLength());
        belief.getMean().setElement(1, 0d);

        break;

      }

      /*
       * Continue along edges
       */
      distTraveled += direction * sampledPathEdge.getInferredEdge().getLength();
      currentEdge = sampledPathEdge;
      currentPath.add(sampledPathEdge);

    }

    return new InferredPath(ImmutableList.copyOf(currentPath));
  }

  public long getSeed() {
    return seed;
  }

  public Random getRng() {
    return rng;
  }

  public InferredGraph getInferredGraph() {
    return inferredGraph;
  }

  public InitialParameters getParameters() {
    return parameters;
  }

  public InferenceInstance getInstance() {
    return instance;
  }

  public SimulationParameters getSimParameters() {
    return simParameters;
  }

}

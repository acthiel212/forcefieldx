/**
 * Title: Force Field X.
 * <p>
 * Description: Force Field X - Software for Molecular Biophysics.
 * <p>
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2018.
 * <p>
 * This file is part of Force Field X.
 * <p>
 * Force Field X is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 as published by
 * the Free Software Foundation.
 * <p>
 * Force Field X is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place, Suite 330, Boston, MA 02111-1307 USA
 * <p>
 * Linking this library statically or dynamically with other modules is making a
 * combined work based on this library. Thus, the terms and conditions of the
 * GNU General Public License cover the whole combination.
 * <p>
 * As a special exception, the copyright holders of this library give you
 * permission to link this library with independent modules to produce an
 * executable, regardless of the license terms of these independent modules, and
 * to copy and distribute the resulting executable under terms of your choice,
 * provided that you also meet, for each linked independent module, the terms
 * and conditions of the license of that module. An independent module is a
 * module which is not derived from or based on this library. If you modify this
 * library, you may extend this exception to your version of the library, but
 * you are not obligated to do so. If you do not wish to do so, delete this
 * exception statement from your version.
 */
package ffx.algorithms.osrw;

import java.util.logging.Level;
import java.util.logging.Logger;
import static java.lang.String.format;

import org.apache.commons.configuration2.CompositeConfiguration;

import ffx.algorithms.AlgorithmListener;
import ffx.algorithms.integrators.IntegratorEnum;
import ffx.algorithms.mc.BoltzmannMC;
import ffx.algorithms.mc.LambdaMove;
import ffx.algorithms.mc.MDMove;
import ffx.algorithms.thermostats.ThermostatEnum;
import ffx.numerics.Potential;
import ffx.potential.MolecularAssembly;

/**
 * Sample a thermodynamic path using the OSRW method, with the time-dependent
 * bias built up using Metropolis Monte Carlo steps.
 * <p>
 * The algorithm generates coordinate (X) MC moves using molecular dynamics at a
 * fixed lambda value (i.e. using OpenMM), followed by MC lambda moves.
 * <p>
 * 1.) At a fixed Lambda, run a defined length MD trajectory to "move"
 * coordinates and dU/dL on an approximate potential U* (i.e. no OSRW Bias).
 * <p>
 * 2.) Accept / Reject the MD move with probability exp[-Beta(dU - dU*)] where
 * dU is the change in AMOEBA + Bias energy and dU* is the change in AMOEBA +
 * Kinetic energy from the MD.
 * <p>
 * 3.) Randomly change the value of Lambda.
 * <p>
 * 4.) Accept / Reject the Lambda move using the AMOEBA + OSRW Bias energy.
 * <p>
 * 5.) Add to the time dependent 2D bias using the current values of Lambda and
 * dU/dL.
 *
 * @author Michael J. Schnieders
 * @author Hernan Beranbe
 * @author Mallory R. Tollefson
 * @since 1.0
 */
public class MonteCarloOSRW extends BoltzmannMC {

    /**
     * Logger object to print out information for this class.
     */
    private static final Logger logger = Logger.getLogger(MonteCarloOSRW.class.getName());
    /**
     * Potential object used to retrieve the coordinates for the system.
     */
    private final Potential potential;
    /**
     * OSRW object used to retrieve OSRW energy throughout the simulation.
     */
    private final AbstractOSRW osrw;
    /**
     * Double that keeps track of our lambda value.
     */
    private double lambda = 0.0;
    /**
     * MDMove object for completing MC-OSRW molecular dynamics moves.
     */
    private MDMove mdMove;
    /**
     * Total number of steps to take for MC-OSRW sampling.
     */
    private int totalSteps = 10000000;
    /**
     * Number of steps to take per MC-OSRW round.
     */
    private int stepsPerMove = 50;
    /**
     * Lambda move object for completing MC-OSRW lambda moves.
     */
    private LambdaMove lambdaMove;
    /**
     * Boolean that tells algorithm that we are in the equilibration phase of
     * MC-OSRW.
     */
    private boolean equilibration = false;
    /**
     * Convert nanoseconds to seconds.
     */
    private static final double NS2SEC = 1e-9;

    /**
     * <p>
     * Constructor for MonteCarloOSRW.</p>
     *
     * @param potentialEnergy     a {@link ffx.numerics.Potential} object.
     * @param osrw                a {@link AbstractOSRW} object.
     * @param molecularAssembly   a {@link ffx.potential.MolecularAssembly}
     *                            object.
     * @param properties          a
     *                            {@link org.apache.commons.configuration2.CompositeConfiguration} object.
     * @param listener            a {@link ffx.algorithms.AlgorithmListener} object.
     * @param requestedThermostat a
     *                            {@link ffx.algorithms.thermostats.ThermostatEnum} object.
     * @param requestedIntegrator a
     *                            {@link ffx.algorithms.integrators.IntegratorEnum} object.
     */
    public MonteCarloOSRW(Potential potentialEnergy, AbstractOSRW osrw,
                          MolecularAssembly molecularAssembly, CompositeConfiguration properties,
                          AlgorithmListener listener, ThermostatEnum requestedThermostat, IntegratorEnum requestedIntegrator) {
        this.potential = potentialEnergy;
        this.osrw = osrw;

        /**
         * Create the MC MD and Lambda moves.
         */
        mdMove = new MDMove(molecularAssembly, potential, properties, listener, requestedThermostat, requestedIntegrator);
        if (properties.containsKey("randomseed")) {
            int randomSeed = properties.getInt("randomseed", 0);
            logger.info(String.format(" Setting random seed for lambdaMove to %d ", randomSeed));
            lambdaMove = new LambdaMove(randomSeed, lambda, osrw);
            setRandomSeed(randomSeed);
        } else {
            lambdaMove = new LambdaMove(lambda, osrw);
        }

        /**
         * Changing the value of lambda will be handled by this class, as well
         * as adding the time dependent bias.
         */
        osrw.setPropagateLambda(false);

    }

    /**
     * Takes in parameters and calls the MDMove method setMDParameters to update
     * the stepsPerMove and timeStep parameters to the current value in this
     * class
     *
     * @param totalSteps   a int.
     * @param stepsPerMove a int.
     * @param timeStep     a double.
     */
    public void setMDMoveParameters(int totalSteps, int stepsPerMove, double timeStep) {
        this.totalSteps = totalSteps;
        this.stepsPerMove = stepsPerMove;
        mdMove.setMDParameters(stepsPerMove, timeStep);
    }

    /**
     * Calls on LambdaMove class method setLambdaStdDev to update the lambda
     * standard deviation to the current value in this class
     *
     * @param stdDev a double.
     */
    public void setLambdaStdDev(double stdDev) {
        lambdaMove.setStdDev(stdDev);
    }

    /**
     * Sets the value of the boolean equilibration variables to true or false to
     * either allow an equilibration step or skip it.
     *
     * @param equilibration a boolean.
     */
    public void setEquilibration(boolean equilibration) {
        this.equilibration = equilibration;
    }

    /**
     * Calls on the OSRW method set lambda to update lambda to the current value
     * in this class
     *
     * @param lambda a double.
     */
    public void setLambda(double lambda) {
        this.lambda = lambda;
        osrw.setLambda(lambda);
    }

    /**
     * Returns the current value of lambda
     *
     * @return lambda
     */
    public double getLambda() {
        return lambda;
    }

    /**
     * The goal is to sample coordinates (X) and converge "dU/dL" for every
     * state (lambda) along the thermodynamic path.
     * <p>
     * 1.) At a fixed lambda, run a defined length MD trajectory to "move"
     * coordinates and dU/dL.
     * <p>
     * 2.) Accept / Reject the MD move using the OSRW energy.
     * <p>
     * 3.) Randomly change the value of Lambda.
     * <p>
     * 4.) Accept / Reject the Lambda move using the OSRW energy.
     * <p>
     * 5.) Add to the bias.
     */
    public void sampleTwoStep() {

        int n = potential.getNumberOfVariables();
        double[] gradient = new double[n];
        double[] currentCoordinates = new double[n];
        double[] proposedCoordinates = new double[n];
        int numMoves = totalSteps / stepsPerMove;
        int acceptLambda = 0;
        int acceptMD = 0;

        // Initialize the current coordinates.
        potential.getCoordinates(currentCoordinates);

        // Compute the current OSRW potential energy.
        double currentOSRWEnergy = osrw.energyAndGradient(currentCoordinates, gradient);

        // Collect the current dU/dL, Force Field Energy and Bias Energy.
        double currentdUdL = osrw.getForceFielddEdL();
        double currentForceFieldEnergy = osrw.getForceFieldEnergy();
        double currentBiasEnergy = osrw.getBiasEnergy();

        /**
         * Initialize MC move instances.
         */
        for (int imove = 0; imove < numMoves; imove++) {
            long totalMoveTime = -System.nanoTime();
            long mdMoveAndEvalTime = -System.nanoTime();

            if (equilibration) {
                logger.info(String.format("\n MD Equilibration Round %d", imove + 1));
            } else {
                logger.info(String.format("\n MC-OSRW Round %d\n  MC MD Step", imove + 1));
            }

            /**
             * Run MD in an approximate potential U* (U star) that does not
             * include the OSRW bias.
             */
            long mdMoveTime = -System.nanoTime();
            mdMove.move();
            mdMoveTime += System.nanoTime();
            if (logger.isLoggable(Level.FINE)) {
                logger.fine(String.format("  Total time for MD move: %6.3f", mdMoveTime * NS2SEC));
            }

            // Get the starting and final kinetic energy for the MD move.
            double currentKineticEnergy = mdMove.getStartingKineticEnergy();
            double proposedKineticEnergy = mdMove.getKineticEnergy();

            // Get the new coordinates.
            potential.getCoordinates(proposedCoordinates);

            // Compute the Total OSRW Energy as the sum of the Force Field Energy and Bias Energy.
            long proposedOSRWEnergyTime = -System.nanoTime();
            double proposedOSRWEnergy = osrw.energyAndGradient(proposedCoordinates, gradient);
            proposedOSRWEnergyTime += System.nanoTime();

            if (logger.isLoggable(Level.FINE)) {
                logger.fine(String.format("  Time to complete MD OSRW energy method call %6.3f", proposedOSRWEnergyTime * NS2SEC));
            }

            // Retrieve the proposed dU/dL, Force Field Energy and Bias Energy.
            double proposeddUdL = osrw.getForceFielddEdL();
            double proposedForceFieldEnergy = osrw.getForceFieldEnergy();
            double proposedBiasEnergy = osrw.getBiasEnergy();

            // The Metropolis criteria is based on the sum of the OSRW Energy and Kinetic Energy.
            double currentTotalEnergy = currentOSRWEnergy + currentKineticEnergy;
            double proposedTotalEnergy = proposedOSRWEnergy + proposedKineticEnergy;

            logger.info(format("\n  %8s %12s %12s %12s %12s", "", "Kinetic", "Potential", "Bias", "Total"));
            logger.info(format("  Current  %12.3f %12.3f %12.3f %12.3f",
                    currentKineticEnergy, currentForceFieldEnergy, currentBiasEnergy, currentTotalEnergy));
            logger.info(format("  Proposed %12.3f %12.3f %12.3f %12.3f",
                    proposedKineticEnergy, proposedForceFieldEnergy, proposedBiasEnergy, proposedTotalEnergy));
            logger.info(format("  Delta    %12.3f %12.3f %12.3f %12.3f",
                    proposedKineticEnergy - currentKineticEnergy,
                    proposedForceFieldEnergy - currentForceFieldEnergy,
                    proposedBiasEnergy - currentBiasEnergy,
                    proposedTotalEnergy - currentTotalEnergy));

            if (evaluateMove(currentTotalEnergy, proposedTotalEnergy)) {
                /**
                 * Accept MD move.
                 */
                acceptMD++;
                double percent = (acceptMD * 100.0) / (imove + 1);
                logger.info(String.format("\n  Accept [FL=%8.3f,E=%12.4f]\n     -> [FL=%8.3f,E=%12.4f] (%5.1f%%)",
                        currentdUdL, currentOSRWEnergy, proposeddUdL, proposedOSRWEnergy, percent));
                currentOSRWEnergy = proposedOSRWEnergy;
                currentdUdL = proposeddUdL;
                currentForceFieldEnergy = proposedForceFieldEnergy;
                currentBiasEnergy = proposedBiasEnergy;
                System.arraycopy(proposedCoordinates, 0, currentCoordinates, 0, n);
            } else {
                double percent = (acceptMD * 100.0) / (imove + 1);
                logger.info(String.format("\n  Reject [FL=%8.3f,E=%12.4f]\n      -> [FL=%8.3f,E=%12.4f] (%5.1f%%)",
                        currentdUdL, currentOSRWEnergy, proposeddUdL, proposedOSRWEnergy, percent));
                mdMove.revertMove();
            }
            mdMoveAndEvalTime += System.nanoTime();

            if (logger.isLoggable(Level.FINE)) {
                logger.fine(String.format("\n  Total time to run and evaluate MD move: %6.3f", mdMoveAndEvalTime * NS2SEC));
            }

            /**
             * During equilibration, do not change Lambda or contribute to the
             * OSRW bias.
             */
            if (!equilibration) {
                /**
                 * Update Lambda.
                 */
                logger.info("\n  MC Lambda Step");

                long lambdaMoveTime = -System.nanoTime();
                double currentLambda = osrw.getLambda();
                lambdaMove.move();
                double proposedLambda = osrw.getLambda();

                // Compute the Total OSRW Energy as the sum of the Force Field Energy and Bias Energy.
                long proposedOSRWEnergyTime2 = -System.nanoTime();
                proposedOSRWEnergy = osrw.energyAndGradient(currentCoordinates, gradient);
                proposedOSRWEnergyTime2 += System.nanoTime();

                if (logger.isLoggable(Level.FINE)) {
                    logger.info(String.format("  Time to complete Lambda OSRW energy method call %6.3f ", proposedOSRWEnergyTime2 * NS2SEC));
                }

                // Retrieve the proposed dU/dL, Force Field Energy and Bias Energy.
                proposedForceFieldEnergy = osrw.getForceFieldEnergy();
                proposeddUdL = osrw.getForceFielddEdL();

                logger.info(format("\n  Current  OSRW     %12.3f at L=%5.3f.", currentOSRWEnergy, currentLambda));
                logger.info(format("  Proposed OSRW     %12.3f at L=%5.3f.", proposedOSRWEnergy, proposedLambda));
                logger.info(format("  MC Energy change: %12.3f (kcal/mol).", proposedOSRWEnergy - currentOSRWEnergy));

                if (evaluateMove(currentOSRWEnergy, proposedOSRWEnergy)) {
                    acceptLambda++;
                    double percent = (acceptLambda * 100.0) / (imove + 1);
                    logger.info(String.format("  Accept [ L=%8.3f,E=%12.4f]\n      -> [ L=%8.3f,E=%12.4f] (%5.1f%%)",
                            currentLambda, currentOSRWEnergy, proposedLambda, proposedOSRWEnergy, percent));
                    currentForceFieldEnergy = proposedForceFieldEnergy;
                    currentdUdL = proposeddUdL;
                    lambda = proposedLambda;
                } else {
                    double percent = (acceptLambda * 100.0) / (imove + 1);
                    logger.info(String.format("  Reject [ L=%8.3f,E=%12.4f]\n      -> [ L=%8.3f,E=%12.4f] (%5.1f%%)",
                            currentLambda, currentOSRWEnergy, proposedLambda, proposedOSRWEnergy, percent));
                    lambdaMove.revertMove();
                    lambda = currentLambda;
                }

                lambdaMoveTime += System.nanoTime();
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine(String.format("  Lambda move completed in %6.3f", lambdaMoveTime * NS2SEC));
                }

                /**
                 * Update time dependent bias.
                 */
                osrw.addBias(currentdUdL, currentCoordinates, null);

                if (logger.isLoggable(Level.FINE)) {
                    logger.fine(format("  Added Bias at [L=%5.3f, FL=%9.3f]", lambda, currentdUdL));
                }

                // Compute the updated OSRW bias.
                currentBiasEnergy = osrw.computeBiasEnergy(currentLambda, currentdUdL);

                // Update the current OSRW Energy to be the sum of the current Force Field Energy and updated OSRW Bias.
                currentOSRWEnergy = currentForceFieldEnergy + currentBiasEnergy;
            }

            totalMoveTime += System.nanoTime();
            logger.info(format(" MC-OSRW round in %6.3f sec.", totalMoveTime * NS2SEC));
        }
    }

    public void sampleOneStep() {

        int n = potential.getNumberOfVariables();
        double[] gradient = new double[n];
        double[] currentCoordinates = new double[n];
        double[] proposedCoordinates = new double[n];
        int numMoves = totalSteps / stepsPerMove;
        int acceptMD = 0;
        int acceptMCOSRW = 0;

        // Initialize the current coordinates.
        potential.getCoordinates(currentCoordinates);

        // Compute the Total OSRW Energy as the sum of the Force Field Energy and Bias Energy.
        double currentOSRWEnergy = osrw.energyAndGradient(currentCoordinates, gradient);

        // Retrieve the computed dU/dL, Force Field Energy and Bias Energy.
        double currentdUdL = osrw.getForceFielddEdL();
        double currentForceFieldEnergy = osrw.getForceFieldEnergy();
        double currentBiasEnergy = osrw.getBiasEnergy();

        /**
         * Initialize MC move instances.
         */
        for (int imove = 0; imove < numMoves; imove++) {
            long totalMoveTime = -System.nanoTime();

            double currentLambda = osrw.getLambda();
            double proposedLambda = currentLambda;

            if (equilibration) {
                logger.info(String.format("\n MD Equilibration Round %d", imove + 1));
            } else {
                logger.info(String.format("\n MC-OSRW Round %d", imove + 1));
                lambdaMove.move();
                proposedLambda = osrw.getLambda();
            }

            if (logger.isLoggable(Level.FINE)) {
                logger.fine(String.format(" Starting force field energy for move %f", currentForceFieldEnergy));
            }

            /**
             * Run MD in an approximate potential U* (U star) that does not
             * include the OSRW bias.
             */
            long mdMoveTime = -System.nanoTime();
            mdMove.move();
            mdMoveTime += System.nanoTime();
            if (logger.isLoggable(Level.FINE)) {
                logger.fine(String.format(" Total time for MD move: %6.3f", mdMoveTime * NS2SEC));
            }

            // Get the starting and final kinetic energy for the MD move.
            double currentKineticEnergy = mdMove.getStartingKineticEnergy();
            double proposedKineticEnergy = mdMove.getKineticEnergy();

            // Get the new coordinates.
            potential.getCoordinates(proposedCoordinates);

            long proposedOSRWEnergyTime = 0;
            proposedOSRWEnergyTime = -System.nanoTime();

            // Compute the Total OSRW Energy as the sum of the Force Field Energy and Bias Energy.
            double proposedOSRWEnergy = osrw.energyAndGradient(proposedCoordinates, gradient);
            proposedOSRWEnergyTime += System.nanoTime();
            if (logger.isLoggable(Level.FINE)) {
                logger.fine(String.format(" Time to complete MD OSRW energy method call %6.3f", proposedOSRWEnergyTime * NS2SEC));
            }

            // Retrieve the proposed dU/dL, Force Field Energy and Bias Energy.
            double proposeddUdL = osrw.getForceFielddEdL();
            double proposedForceFieldEnergy = osrw.getForceFieldEnergy();
            double proposedBiasEnergy = osrw.getBiasEnergy();

            // The Metropolis criteria is based on the sum of the OSRW Energy and Kinetic Energy.
            double currentTotalEnergy = currentOSRWEnergy + currentKineticEnergy;
            double proposedTotalEnergy = proposedOSRWEnergy + proposedKineticEnergy;

            logger.info(format("\n  %8s %12s %12s %12s %12s", "", "Kinetic", "Potential", "Bias", "Total"));
            logger.info(format("  Current  %12.3f %12.3f %12.3f %12.3f",
                    currentKineticEnergy, currentForceFieldEnergy, currentBiasEnergy, currentTotalEnergy));
            logger.info(format("  Proposed %12.3f %12.3f %12.3f %12.3f",
                    proposedKineticEnergy, proposedForceFieldEnergy, proposedBiasEnergy, proposedTotalEnergy));
            logger.info(format("  Delta    %12.3f %12.3f %12.3f %12.3f",
                    proposedKineticEnergy - currentKineticEnergy,
                    proposedForceFieldEnergy - currentForceFieldEnergy,
                    proposedBiasEnergy - currentBiasEnergy,
                    proposedTotalEnergy - currentTotalEnergy));

            if (equilibration) {
                if (evaluateMove(currentTotalEnergy, proposedTotalEnergy)) {
                    /**
                     * Accept MD.
                     */
                    acceptMD++;
                    double percent = (acceptMD * 100.0) / (imove + 1);
                    logger.info(String.format("\n Accept [ FL=%8.3f, E=%12.4f]\n     -> [ FL=%8.3f, E=%12.4f] (%5.1f%%)",
                            currentdUdL, currentOSRWEnergy, proposeddUdL, proposedOSRWEnergy, percent));
                    currentOSRWEnergy = proposedOSRWEnergy;
                    currentdUdL = proposeddUdL;
                    currentForceFieldEnergy = proposedForceFieldEnergy;
                    currentBiasEnergy = proposedBiasEnergy;
                    System.arraycopy(proposedCoordinates, 0, currentCoordinates, 0, n);
                } else {
                    double percent = (acceptMD * 100.0) / (imove + 1);
                    logger.info(String.format("\n Reject [ FL=%8.3f, E=%12.4f]\n     -> [ FL=%8.3f, E=%12.4f] (%5.1f%%)",
                            currentdUdL, currentOSRWEnergy, proposeddUdL, proposedOSRWEnergy, percent));
                    mdMove.revertMove();
                }
            } else {
                if (evaluateMove(currentTotalEnergy, proposedTotalEnergy)) {
                    acceptMCOSRW++;
                    double percent = (acceptMCOSRW * 100.0) / (imove + 1);
                    logger.info(String.format("\n Accept [ L=%8.3f, FL=%8.3f, E=%12.4f]\n    -> [ L=%8.3f, FL=%8.3f, E=%12.4f] (%5.1f%%)",
                            currentLambda, currentdUdL, currentOSRWEnergy, proposedLambda, proposeddUdL, proposedOSRWEnergy, percent));
                    lambda = proposedLambda;
                    currentdUdL = proposeddUdL;
                    currentForceFieldEnergy = proposedForceFieldEnergy;
                    System.arraycopy(proposedCoordinates, 0, currentCoordinates, 0, n);
                } else {
                    double percent = (acceptMCOSRW * 100.0) / (imove + 1);
                    logger.info(String.format("\n Reject [ L=%8.3f, FL=%8.3f, E=%12.4f]\n    -> [ L=%8.3f, FL=%8.3f, E=%12.4f] (%5.1f%%)",
                            currentLambda, currentdUdL, currentOSRWEnergy, proposedLambda, proposeddUdL, proposedOSRWEnergy, percent));
                    lambdaMove.revertMove();
                    lambda = currentLambda;
                    mdMove.revertMove();
                }

                /**
                 * Update time-dependent bias.
                 */
                osrw.addBias(currentdUdL, currentCoordinates, null);

                if (logger.isLoggable(Level.FINE)) {
                    logger.fine(format(" Added Bias at [ L=%5.3f, FL=%9.3f]", lambda, currentdUdL));
                }

                // Compute the updated OSRW bias.
                currentBiasEnergy = osrw.computeBiasEnergy(currentLambda, currentdUdL);

                // Update the current OSRW Energy to be the sum of the current Force Field Energy and updated OSRW Bias.
                currentOSRWEnergy = currentForceFieldEnergy + currentBiasEnergy;

            }

            totalMoveTime += System.nanoTime();
            logger.info(format(" MC-OSRW round in %6.3f sec.", totalMoveTime * NS2SEC));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected double currentEnergy() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void storeState() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void revertStep() {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
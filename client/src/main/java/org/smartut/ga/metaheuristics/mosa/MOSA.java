/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * Copyright (C) 2021- SmartUt contributors
 *
 * SmartUt is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * SmartUt is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with SmartUt. If not, see <http://www.gnu.org/licenses/>.
 */
package org.smartut.ga.metaheuristics.mosa;

import org.smartut.ClientProcess;
import org.smartut.Properties;
import org.smartut.ga.ChromosomeFactory;
import org.smartut.ga.comparators.OnlyCrowdingComparator;
import org.smartut.ga.operators.ranking.CrowdingDistance;
import org.smartut.ga.operators.selection.BestKSelection;
import org.smartut.ga.operators.selection.RandomKSelection;
import org.smartut.ga.operators.selection.RankSelection;
import org.smartut.ga.operators.selection.SelectionFunction;
import org.smartut.rmi.ClientServices;
import org.smartut.rmi.service.ClientNodeLocal;
import org.smartut.statistics.RuntimeVariable;
import org.smartut.testcase.TestChromosome;
import org.smartut.testcase.TestFitnessFunction;
import org.smartut.utils.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Implementation of the Many-Objective Sorting Algorithm (MOSA) described in the
 * paper "Reformulating branch coverage as a many-objective optimization problem".
 *
 * @author Annibale Panichella, Fitsum M. Kifetew
 */
public class MOSA extends AbstractMOSA {

	private static final long serialVersionUID = 146182080947267628L;

	private static final Logger logger = LoggerFactory.getLogger(MOSA.class);

	/** immigrant groups from neighbouring client */
	private final ConcurrentLinkedQueue<List<TestChromosome>> immigrants =
			new ConcurrentLinkedQueue<>();

	private final SelectionFunction<TestChromosome> emigrantsSelection;

	/** Crowding distance measure to use */
	protected CrowdingDistance<TestChromosome> distance = new CrowdingDistance<>();

	/**
	 * Constructor based on the abstract class {@link AbstractMOSA}
	 * @param factory
	 */
	public MOSA(ChromosomeFactory<TestChromosome> factory) {
		super(factory);

		switch (Properties.EMIGRANT_SELECTION_FUNCTION) {
			case RANK:
				this.emigrantsSelection = new RankSelection<>();
				break;
			case RANDOMK:
				this.emigrantsSelection = new RandomKSelection<>();
				break;
			default:
				this.emigrantsSelection = new BestKSelection<>();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void evolve() {
		List<TestChromosome> offspringPopulation = this.breedNextGeneration();

		// Create the union of parents and offSpring
		List<TestChromosome> union = new ArrayList<>();
		union.addAll(this.population);
		union.addAll(offspringPopulation);

		// for parallel runs: integrate possible immigrants
		if (Properties.NUM_PARALLEL_CLIENTS > 1 && !immigrants.isEmpty()) {
			union.addAll(immigrants.poll());
		}

		Set<TestFitnessFunction> uncoveredGoals = this.getUncoveredGoals();

		// Ranking the union
		logger.debug("Union Size =" + union.size());
		// Ranking the union using the best rank algorithm (modified version of the non dominated sorting algorithm)
		this.rankingFunction.computeRankingAssignment(union, uncoveredGoals);

		int remain = this.population.size();
		int index = 0;
		List<TestChromosome> front = null;
		this.population.clear();

		// Obtain the next front
		front = this.rankingFunction.getSubfront(index);

		while ((remain > 0) && (remain >= front.size()) && !front.isEmpty()) {
			// Assign crowding distance to individuals
			this.distance.fastEpsilonDominanceAssignment(front, uncoveredGoals);
			// Add the individuals of this front
			this.population.addAll(front);

			// Decrement remain
			remain = remain - front.size();

			// Obtain the next front
			index++;
			if (remain > 0) {
				front = this.rankingFunction.getSubfront(index);
			}
		}

		// Remain is less than front(index).size, insert only the best one
		if (remain > 0 && !front.isEmpty()) { // front contains individuals to insert
			this.distance.fastEpsilonDominanceAssignment(front, uncoveredGoals);
			front.sort(new OnlyCrowdingComparator<>());
			for (int k = 0; k < remain; k++) {
				this.population.add(front.get(k));
			}

			remain = 0;
		}

		// for parallel runs: collect best k individuals for migration
		if (Properties.NUM_PARALLEL_CLIENTS > 1 && Properties.MIGRANTS_ITERATION_FREQUENCY > 0) {
			if ((currentIteration + 1) % Properties.MIGRANTS_ITERATION_FREQUENCY == 0 && !this.population.isEmpty()) {
				HashSet<TestChromosome> emigrants = new HashSet<>(emigrantsSelection.select(this.population,
						Properties.MIGRANTS_COMMUNICATION_RATE));
				ClientServices.<TestChromosome>getInstance().getClientNode().emigrate(emigrants);
			}
		}

		this.currentIteration++;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void generateSolution() {
		logger.info("executing generateSolution function");

		// keep track of covered goals
		this.fitnessFunctions.forEach(this::addUncoveredGoal);

		// initialize population
		if (this.population.isEmpty()) {
			this.initializePopulation();
		}

		// Calculate dominance ranks and crowding distance
		this.rankingFunction.computeRankingAssignment(this.population, this.getUncoveredGoals());
		for (int i = 0; i < this.rankingFunction.getNumberOfSubfronts(); i++) {
			this.distance.fastEpsilonDominanceAssignment(this.rankingFunction.getSubfront(i), this.getUncoveredGoals());
		}

		final ClientNodeLocal<TestChromosome> clientNode =
				ClientServices.<TestChromosome>getInstance().getClientNode();

		Listener<Set<TestChromosome>> listener = null;
		if (Properties.NUM_PARALLEL_CLIENTS > 1) {
			listener = event -> immigrants.add(new LinkedList<>(event));
			clientNode.addListener(listener);
		}

		// TODO add here dynamic stopping condition
		while (!this.isFinished() && this.getNumberOfUncoveredGoals() > 0) {
			this.evolve();
			this.notifyIteration();
		}

		if (Properties.NUM_PARALLEL_CLIENTS > 1) {
			clientNode.deleteListener(listener);

			if (ClientProcess.DEFAULT_CLIENT_NAME.equals(ClientProcess.getIdentifier())) {
				//collect all end result test cases
				Set<Set<TestChromosome>> collectedSolutions = clientNode.getBestSolutions();

				logger.debug(ClientProcess.DEFAULT_CLIENT_NAME + ": Received " + collectedSolutions.size() + " solution sets");
				for (Set<TestChromosome> solution : collectedSolutions) {
					for (TestChromosome t : solution) {
						this.calculateFitness(t);
					}
				}
			} else {
				//send end result test cases to Client-0
				Set<TestChromosome> solutionsSet = new HashSet<>(getSolutions());
				logger.debug(ClientProcess.getPrettyPrintIdentifier() + "Sending " + solutionsSet.size()
						+ " solutions to " + ClientProcess.DEFAULT_CLIENT_NAME);
				clientNode.sendBestSolution(solutionsSet);
			}
		}

		// storing the time needed to reach the maximum coverage
		clientNode.trackOutputVariable(RuntimeVariable.Time2MaxCoverage,
				this.budgetMonitor.getTime2MaxCoverage());
		this.notifySearchFinished();
	}
}

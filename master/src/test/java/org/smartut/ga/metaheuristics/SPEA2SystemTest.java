/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and SmartUt
 * contributors
 *
 * This file is part of SmartUt.
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
package org.smartut.ga.metaheuristics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import org.smartut.SmartUt;
import org.smartut.Properties;
import org.smartut.Properties.Algorithm;
import org.smartut.Properties.Criterion;
import org.smartut.Properties.StoppingCondition;
import org.smartut.coverage.ambiguity.AmbiguityCoverageFactory;
import org.smartut.coverage.rho.RhoCoverageFactory;
import org.smartut.SystemTestBase;
import org.smartut.ga.Chromosome;
import org.smartut.ga.FitnessFunction;
import org.smartut.ga.problems.metrics.Spacing;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.examples.with.different.packagename.BMICalculator;

import java.util.ArrayList;
import java.util.List;

/**
 * SPEA2SystemTest.
 * 
 * @author José Campos
 */
public class SPEA2SystemTest extends SystemTestBase {

  @Before
  public void reset() {
    RhoCoverageFactory.getGoals().clear();
    AmbiguityCoverageFactory.getGoals().clear();
  }

  public double[][] test(String targetClass) {
    Properties.CRITERION = new Criterion[2];
    Properties.CRITERION[0] = Criterion.RHO;
    Properties.CRITERION[1] = Criterion.AMBIGUITY;

    Properties.ALGORITHM = Algorithm.SPEA2;
    Properties.SELECTION_FUNCTION = Properties.SelectionFunction.BINARY_TOURNAMENT;
    Properties.STOPPING_CONDITION = StoppingCondition.MAXGENERATIONS;
    Properties.MINIMIZE = false;

    SmartUt smartut = new SmartUt();

    Properties.TARGET_CLASS = targetClass;

    String[] command = new String[] {"-generateSuite", "-class", targetClass};

    Object result = smartut.parseCommandLine(command);
    Assert.assertNotNull(result);

    GeneticAlgorithm<?> ga = getGAFromResult(result);

    final FitnessFunction<?> branch = ga.getFitnessFunctions().get(0);
    final FitnessFunction<?> rho = ga.getFitnessFunctions().get(1);

    List<Chromosome> population = new ArrayList<>(ga.getBestIndividuals());

    double[][] front = new double[population.size()][2];
    for (int i = 0; i < population.size(); i++) {
      Chromosome c = population.get(i);
      front[i][0] = c.getFitness(branch);
      front[i][1] = c.getFitness(rho);
    }

    return front;
  }

  @Test
  public void nonMinimalSpacing() {
    String targetClass = BMICalculator.class.getCanonicalName();

    Properties.POPULATION = 50;
    Properties.SEARCH_BUDGET = 10;
    double[][] front = test(targetClass);

    for (int i = 0; i < front.length; i++) {
      assertNotEquals(front[i][0], front[i][1], 0.0);
    }

    Spacing sp = new Spacing();
    double[] max = sp.getMaximumValues(front);
    double[] min = sp.getMinimumValues(front);

    double[][] frontNormalized = sp.getNormalizedFront(front, max, min);
    assertNotEquals(0.0, sp.evaluate(frontNormalized), 0.0);
  }

  @Test
  public void minimalSpacing() {
    String targetClass = BMICalculator.class.getCanonicalName();

    Properties.POPULATION = 10;
    Properties.SEARCH_BUDGET = 40;
    double[][] front = test(targetClass);

    Spacing sp = new Spacing();
    double[] max = sp.getMaximumValues(front);
    double[] min = sp.getMinimumValues(front);

    double[][] frontNormalized = sp.getNormalizedFront(front, max, min);
    assertEquals(0.0, sp.evaluate(frontNormalized), 0.0);
  }
}

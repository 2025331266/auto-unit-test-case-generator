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

package org.smartut.junit;

import org.smartut.testcase.execution.ExecutionTracer;
import org.smartut.utils.LoggingUtils;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/**
 * <p>
 * JUnitRunListener class
 * </p>
 * 
 * @author José Campos
 */
public class JUnitRunListener extends RunListener {

	
	private JUnitRunner junitRunner = null;

	
	private JUnitResult testResult = null;

	
	private long start;

	/**
	 * 
	 * @param jr
	 */
	public JUnitRunListener(JUnitRunner jR) {
		this.junitRunner = jR;
	}

	/**
	 * Called before any tests have been run
	 */
	@Override
	public void testRunStarted(Description description) {
		LoggingUtils.getSmartUtLogger().info("* Number of test cases to execute: " + description.testCount());
	}

	/**
	 * Called when all tests have finished
	 */
	@Override
	public void testRunFinished(Result result) {
		LoggingUtils.getSmartUtLogger().info("* Number of test cases executed: " + result.getRunCount());
	}

	/**
	 * Called when an atomic test is about to be started
	 */
	@Override
	public void testStarted(Description description) {
		LoggingUtils.getSmartUtLogger().info("* Started: " + "ClassName: " + description.getClassName() + ", MethodName: " + description.getMethodName());

		this.start = System.nanoTime();

		this.testResult = new JUnitResult(description.getClassName() + "#" + description.getMethodName(), this.junitRunner.getJUnitClass());
	}

	/**
	 * Called when an atomic test has finished. whether the test successes or fails
	 */
	@Override
	public void testFinished(Description description) {
		LoggingUtils.getSmartUtLogger().info("* Finished: " + "ClassName: " + description.getClassName() + ", MethodName: " + description.getMethodName());

		this.testResult.setRuntime(System.nanoTime() - this.start);
		this.testResult.setExecutionTrace(ExecutionTracer.getExecutionTracer().getTrace());
		this.testResult.incrementRunCount();
		ExecutionTracer.getExecutionTracer().clear();

		this.junitRunner.addResult(this.testResult);
	}

	/**
	 * Called when an atomic test fails
	 */
	@Override
	public void testFailure(Failure failure) {
		LoggingUtils.getSmartUtLogger().info("* Failure: " + failure.getMessage());
		for (StackTraceElement s : failure.getException().getStackTrace()) {
			LoggingUtils.getSmartUtLogger().info("   " + s.toString());
		}

		this.testResult.setSuccessful(false);
		this.testResult.setTrace(failure.getTrace());
		this.testResult.incrementFailureCount();
	}

	/**
	 * Called when a test will not be run, generally because a test method is annotated with Ignore
	 */
	@Override
	public void testIgnored(Description description) throws java.lang.Exception {
		LoggingUtils.getSmartUtLogger().info("* Ignored: " + "ClassName: " + description.getClassName() + ", MethodName: " + description.getMethodName());
	}
}

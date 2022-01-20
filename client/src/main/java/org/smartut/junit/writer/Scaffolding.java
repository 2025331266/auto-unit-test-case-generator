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
package org.smartut.junit.writer;

import org.apache.commons.lang3.StringEscapeUtils;
import org.smartut.Properties;
import org.smartut.TestGenerationContext;
import org.smartut.instrumentation.BytecodeInstrumentation;
import org.smartut.runtime.GuiSupport;
import org.smartut.runtime.InitializingListener;
import org.smartut.runtime.LoopCounter;
import org.smartut.runtime.RuntimeSettings;
import org.smartut.runtime.agent.InstrumentingAgent;
import org.smartut.runtime.annotation.SmartUtClassExclude;
import org.smartut.runtime.classhandling.ClassResetter;
import org.smartut.runtime.classhandling.ClassStateSupport;
import org.smartut.runtime.classhandling.JDKClassResetter;
import org.smartut.runtime.jvm.ShutdownHookHandler;
import org.smartut.runtime.sandbox.Sandbox;
import org.smartut.runtime.thread.KillSwitchHandler;
import org.smartut.runtime.thread.ThreadStopper;
import org.smartut.runtime.util.JOptionPaneInputs;
import org.smartut.runtime.util.SystemInUtil;
import org.smartut.runtime.vnet.NonFunctionalRequirementRule;
import org.smartut.testcase.execution.ExecutionResult;
import org.smartut.testcase.execution.reset.ClassReInitializer;
import org.smartut.testcase.statements.FunctionalMockStatement;
import org.smartut.testcase.statements.Statement;
import org.smartut.utils.generic.GenericClass;
import org.junit.rules.Timeout;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.smartut.junit.writer.TestSuiteWriterUtils.*;

/**
 * Class used to generate all the scaffolding code that ends up in methods
 * like @After/@Before and that are used to setup the SmartUt framework (eg
 * mocking of classes, reset of static state)
 *
 * @author arcuri
 */
public class Scaffolding {

	public static final String EXECUTOR_SERVICE = "executor";

	private static final String DEFAULT_PROPERTIES = "defaultProperties";

	private static final String THREAD_STOPPER = "threadStopper";

	/**
	 * Return full JUnit code for scaffolding file for the give test
	 *
	 * @param testName
	 * @return
	 */
	public static String getScaffoldingFileContent(String testName, List<ExecutionResult> results,
			boolean wasSecurityException) {

		String name = getFileName(testName);

		StringBuilder builder = new StringBuilder();

		builder.append(getHeader(name, results, wasSecurityException));
		if(results.isEmpty()) {
			builder.append(METHOD_SPACE);
			builder.append("// Empty scaffolding for empty test suite\n");
		} else {
			builder.append(new Scaffolding().getBeforeAndAfterMethods(name, wasSecurityException, results));
		}
		builder.append(getFooter());

		return builder.toString();
	}

	protected static String getFooter() {
		return "}\n";
	}

	protected static String getHeader(String name, List<ExecutionResult> results, boolean wasSecurityException) {
		StringBuilder builder = new StringBuilder();
		builder.append("/**\n");
		builder.append(" * Scaffolding file used to store all the setups needed to run \n");
		builder.append(" * tests automatically generated by SmartUt\n");
		builder.append(" * " + new Date() + "\n");
		builder.append(" */\n\n");

		if (!Properties.CLASS_PREFIX.equals("")) {
			builder.append("package ");
			builder.append(Properties.CLASS_PREFIX);
			builder.append(";\n");
		}
		builder.append("\n");

		for (String imp : getScaffoldingImports(wasSecurityException, results)) {
			builder.append("import ");
			builder.append(imp);
			builder.append(";\n");
		}
		builder.append("\n");

		if(doesUseMocks(results)){
			builder.append("import static "+Mockito.class.getCanonicalName()+".*;\n");
		}

		builder.append("@SmartUtClassExclude\n");
		builder.append(getAdapter().getClassDefinition(name));
		builder.append(" {\n");

		return builder.toString();
	}

	public static String getFileName(String testName) throws IllegalArgumentException {
		if (testName == null || testName.trim().isEmpty()) {
			throw new IllegalArgumentException("Empty test name");
		}
		return testName + "_" + Properties.SCAFFOLDING_SUFFIX;
	}

	/**
	 * Return all classes for which we need an import statement
	 *
	 * @param wasSecurityException
	 * @param results
	 * @return
	 */
	public static List<String> getScaffoldingImports(boolean wasSecurityException, List<ExecutionResult> results) {
		List<String> list = new ArrayList<>();

		list.add(SmartUtClassExclude.class.getCanonicalName());

		if (needToUseAgent() || wasSecurityException || SystemInUtil.getInstance().hasBeenUsed()
				|| JOptionPaneInputs.getInstance().hasAnyDialog() || !Properties.NO_RUNTIME_DEPENDENCY
				|| doesUseMocks(results)) {
			list.add(org.junit.BeforeClass.class.getCanonicalName());
			list.add(org.junit.Before.class.getCanonicalName());
			list.add(org.junit.After.class.getCanonicalName());
		}

		if (wasSecurityException || shouldResetProperties(results)) {
			list.add(org.junit.AfterClass.class.getCanonicalName());
		}

		if (Properties.RESET_STATIC_FIELDS || wasSecurityException) {
			/*
			 * for simplicity, when doing static reset, we always activate the
			 * sandbox, as anyway its code is only going to be in the
			 * scaffolding
			 */
			list.add(Sandbox.class.getCanonicalName());
			list.add(Sandbox.SandboxMode.class.getCanonicalName());
		}

		if (wasSecurityException) {
			list.add(java.util.concurrent.ExecutorService.class.getCanonicalName());
			list.add(java.util.concurrent.Executors.class.getCanonicalName());
			list.add(java.util.concurrent.Future.class.getCanonicalName());
			list.add(java.util.concurrent.TimeUnit.class.getCanonicalName());
		}

		return list;
	}

	/**
	 * Get the code of methods for @BeforeClass, @Before, @AfterClass and
	 *
	 * @return @After.
	 *         <p>
	 *         In those methods, the SmartUt framework for running the
	 *         generated test cases is handled (e.g., use of customized
	 *         SecurityManager and runtime bytecode replacement)
	 */
	public String getBeforeAndAfterMethods(String name, boolean wasSecurityException, List<ExecutionResult> results) {

		/*
		 * Usually, we need support methods (ie @BeforeClass,@Before,@After
		 * and @AfterClass) only if there was a security exception (and so we
		 * need SmartUt security manager, and test runs on separated thread) or
		 * if we are doing bytecode replacement (and so we need to activate
		 * JavaAgent).
		 *
		 * But there are cases that we might always want: eg, setup logging
		 */

		StringBuilder bd = new StringBuilder("");
		bd.append("\n");

		/*
		 * Because this method is perhaps called only once per SUT, not much of
		 * the point to try to optimize it
		 */

		/*
		 * As of JUnit 4.12, Timeout Rule is broken, as it does not
		 * execute @After methods. TODO: put this back (and change @Test) once
		 * this issue is resolved, and new version of JUnit is released. Issue
		 * is reported at:
		 * 
		 * https://github.com/junit-team/junit/issues/1231
		 */
		// generateTimeoutRule(bd);

		generateNFRRule(bd);

		generateFields(bd, wasSecurityException, results);

		generateBeforeClass(bd, wasSecurityException, results);

		generateAfterClass(bd, wasSecurityException, results);

		generateBefore(bd, wasSecurityException, results);

		generateAfter(bd, wasSecurityException);

		generateSetSystemProperties(bd, results);

		generateInitializeClasses(name, bd);

		generateMockInitialization(name, bd, results);

		if (Properties.RESET_STATIC_FIELDS) {
			generateResetClasses(name, bd);
		}

		return bd.toString();
	}


	/**
	 * This is needed  because the first time we do initialize a mock object, that can take
	 * some seconds (successive calls would be based on cached data), and so tests might
	 * timeout. So here we force the mock initialization in a @BeforeClass
	 *
	 * @param bd
	 * @param results
     */
	private void generateMockInitialization(String testClassName, StringBuilder bd, List<ExecutionResult> results) {
		if(! doesUseMocks(results)){
			return;
		}


		// In order to make sure this is called *after* initializeClasses this method is now called directly from initSmartUtFramework
		// bd.append(METHOD_SPACE);
		// bd.append("@BeforeClass \n");

		bd.append(METHOD_SPACE);
		bd.append("private static void initMocksToAvoidTimeoutsInTheTests() throws ClassNotFoundException { \n");

		Set<String> mockStatements = new LinkedHashSet<>();
		for(ExecutionResult er : results) {
			for (Statement st : er.test) {
				if (st instanceof FunctionalMockStatement) {
					FunctionalMockStatement fms = (FunctionalMockStatement) st;
					String name = new GenericClass(fms.getReturnType()).getRawClass().getTypeName();
					mockStatements.add("mock(Class.forName(\""+name+"\", false, "+testClassName + ".class.getClassLoader()));");
				}
			}
		}

		mockStatements.stream()
				.sorted()
				.forEach(m -> {
					bd.append(BLOCK_SPACE);
					bd.append(m);
					bd.append("\n");
				});

		bd.append(METHOD_SPACE);
		bd.append("}\n");
	}

	private void generateNFRRule(StringBuilder bd) {
		bd.append(METHOD_SPACE);
		bd.append("@org.junit.Rule \n");
		bd.append(METHOD_SPACE);
		bd.append("public " + NonFunctionalRequirementRule.class.getName() + " nfr = new "
				+ NonFunctionalRequirementRule.class.getName() + "();\n\n");
	}

	/**
	 * Hanging tests have very, very high negative impact. They can mess up
	 * everything (eg when running "mvn test"). As such, we should always have
	 * timeouts. Adding timeouts only in certain conditions is too risky
	 *
	 * @param bd
	 */
	private void generateTimeoutRule(StringBuilder bd) {
		bd.append(METHOD_SPACE);
		bd.append("@org.junit.Rule \n");
		bd.append(METHOD_SPACE);
		int timeout = Properties.TIMEOUT + 1000;
		bd.append("public " + Timeout.class.getName() + " globalTimeout = new " + Timeout.class.getName() + "("
				+ timeout);

		boolean useNew = false;
		try {
			// FIXME: this check does not seem to work properly :(
			Class<?> timeoutOfSUTJunit = TestGenerationContext.getInstance().getClassLoaderForSUT()
					.loadClass(Timeout.class.getName());
			Constructor c = timeoutOfSUTJunit.getDeclaredConstructor(Long.TYPE, TimeUnit.class);
			useNew = true;
		} catch (ClassNotFoundException e) {
			logger.error("Failed to load Timeout rule from SUT classloader: {}", e.getMessage(), e);
		} catch (NoSuchMethodException e) {
			logger.warn("SUT is using an old version of JUnit");
			useNew = false;
		}

		if (useNew) {
			// TODO: put back once above check works
			// bd.append(", " + TimeUnit.class.getName() + ".MILLISECONDS");
		}
		bd.append("); \n");
		bd.append("\n");
	}

	private void generateResetClasses(String testClassName, StringBuilder bd) {
		List<String> classesToReset = ClassReInitializer.getInstance().getInitializedClasses();

		bd.append("\n");
		bd.append(METHOD_SPACE);
		bd.append("private static void resetClasses() {\n");

		if (classesToReset.size() != 0) {

			//为了resetCUT，所以在initializeClasses时会将ClassLoader进行set，生成的用例不必在resetClasses里再进行set
//			bd.append(BLOCK_SPACE);
//			bd.append(ClassResetter.class.getName() + ".getInstance().setClassLoader(");
//			bd.append(testClassName + ".class.getClassLoader()); \n\n");

			bd.append(BLOCK_SPACE);
			bd.append(ClassStateSupport.class.getName() + ".resetClasses(");

			//需要reset的class不列在resetClasses里，新增将在ClassStateSupport中缓存needResetClass
//			for (int i = 0; i < classesToReset.size(); i++) {
//				String className = classesToReset.get(i);
//				bd.append("\n" + INNER_BLOCK_SPACE + "\"" + className + "\"");
//				if (i < classesToReset.size() - 1) {
//					bd.append(",");
//				}
//			}
//
//			bd.append("\n");
//			bd.append(BLOCK_SPACE);
			bd.append(");\n");
		}

		bd.append(METHOD_SPACE);
		bd.append("}" + "\n");
	}

	/**
	 * Here we need to filter out all classes that cannot/should not be loaded,
	 * like for example tmp tests generated by SmartUt
	 *
	 * @return a new instantiated list
	 */
	private List<String> getClassesToInit(List<String> allInstrumentedClasses) {

		List<String> classes = new ArrayList<>();

		for (String name : allInstrumentedClasses) {
			// check for generated tests
			if (name.contains(Properties.TARGET_CLASS)
					&& (name.endsWith(Properties.JUNIT_SUFFIX) || name.endsWith(Properties.SCAFFOLDING_SUFFIX))) {
				continue;
			}

			classes.add(name);

		}

		return classes;
	}

	private void generateInitializeClasses(String testClassName, StringBuilder bd) {

		// if (Properties.NO_RUNTIME_DEPENDENCY) // Jose: this makes the test
		// suite not compile
		// return; // when test_scaffolding=false and no_runtime_dependency=true

		List<String> allInstrumentedClasses = TestGenerationContext.getInstance().getClassLoaderForSUT()
				.getViewOfInstrumentedClasses();
		List<String> classesToInit = getClassesToInit(allInstrumentedClasses);

		bd.append("\n");
		bd.append(METHOD_SPACE);
		bd.append("private static void " + InitializingListener.INITIALIZE_CLASSES_METHOD + "() {\n");

		if (classesToInit.size() != 0) {
			bd.append(BLOCK_SPACE);
			bd.append(ClassStateSupport.class.getName() + ".initializeClasses(");
			bd.append(testClassName + ".class.getClassLoader() ");

			for (String className : classesToInit) {
				if (!BytecodeInstrumentation.checkIfCanInstrument(className)) {
					continue;
				}
				bd.append(",\n" + INNER_BLOCK_SPACE + "\"" + className + "\"");
			}
			bd.append("\n");
			bd.append(BLOCK_SPACE);
			bd.append(");\n");
		}

		/*
		 * Not needed any longer, since the issue was fixed with a
		 * customized @RunWith
		 *
		 * bd.append("\n");
		 * 
		 * List<String> allInstrumentedClasses =
		 * TestGenerationContext.getInstance().getClassLoaderForSUT().
		 * getViewOfInstrumentedClasses();
		 * 
		 * //this have to be done AFTER the classes have been loaded in a
		 * specific order bd.append(BLOCK_SPACE);
		 * bd.append(ClassStateSupport.class.getName()+".retransformIfNeeded(");
		 * bd.append(testClassName+ ".class.getClassLoader()");
		 * 
		 * for(int i=0; i<allInstrumentedClasses.size(); i++){ String s =
		 * allInstrumentedClasses.get(i); bd.append(",\n");
		 * bd.append(INNER_BLOCK_SPACE); bd.append("\""+s+"\""); }
		 * bd.append("\n"); bd.append(BLOCK_SPACE); bd.append(");\n");
		 */

		bd.append(METHOD_SPACE);
		bd.append("} \n");
	}

	private void generateAfter(StringBuilder bd, boolean wasSecurityException) {

		if (Properties.NO_RUNTIME_DEPENDENCY)
			return;

		/*
		 * Likely always at least ThreadStopper
		 *
		 * if (!Properties.RESET_STANDARD_STREAMS && !wasSecurityException &&
		 * !Properties.REPLACE_CALLS && !Properties.VIRTUAL_FS &&
		 * !Properties.RESET_STATIC_FIELDS) { return; }
		 */

		bd.append(METHOD_SPACE);
		bd.append("@After \n");
		bd.append(METHOD_SPACE);
		bd.append("public void doneWithTestCase(){ \n");

		bd.append(BLOCK_SPACE);
		bd.append(THREAD_STOPPER + ".killAndJoinClientThreads();\n");

		if (Properties.REPLACE_CALLS) {
			bd.append(BLOCK_SPACE);
			bd.append(ShutdownHookHandler.class.getName() + ".getInstance().safeExecuteAddedHooks(); \n");
		}

		if (Properties.RESET_STANDARD_STREAMS) {
			bd.append(BLOCK_SPACE);
			bd.append("java.lang.System.setErr(systemErr); \n");

			bd.append(BLOCK_SPACE);
			bd.append("java.lang.System.setOut(systemOut); \n");

			bd.append(BLOCK_SPACE);
			bd.append("DebugGraphics.setLogStream(logStream); \n");
		}

		if (Properties.RESET_STATIC_FIELDS) {
			bd.append(BLOCK_SPACE);
			bd.append(JDKClassResetter.class.getName() + ".reset(); \n");

			//增加resetCUT方法，每个用例执行完后reset被测类static reset方法
			//不reset所有的class 减少执行时间
			bd.append(BLOCK_SPACE);
			bd.append(ClassStateSupport.class.getName()).append(".resetCUT(); \n");
//			bd.append("resetClasses(); \n");
		}

		if (Properties.RESET_STATIC_FIELDS || wasSecurityException) {
			bd.append(BLOCK_SPACE);
			bd.append(Sandbox.class.getName() + ".doneWithExecutingSUTCode(); \n");
		}

		if (needToUseAgent()) {
			bd.append(BLOCK_SPACE);
			bd.append(InstrumentingAgent.class.getName() + ".deactivate(); \n");
		}

		// TODO: see comment in @Before
		if (Properties.HEADLESS_MODE) {
			bd.append(BLOCK_SPACE);
			bd.append(org.smartut.runtime.GuiSupport.class.getName() + ".restoreHeadlessMode(); \n");
		}

		bd.append(METHOD_SPACE);
		bd.append("} \n");

		bd.append("\n");
	}

	private void generateBefore(StringBuilder bd, boolean wasSecurityException, List<ExecutionResult> results) {

		if (Properties.NO_RUNTIME_DEPENDENCY)
			return;

		/*
		 * Most likely, should always have at least ThreadStopper
		 *
		 * if (!Properties.RESET_STANDARD_STREAMS &&
		 * !TestSuiteWriterUtils.shouldResetProperties(results) &&
		 * !wasSecurityException && !Properties.REPLACE_CALLS &&
		 * !Properties.VIRTUAL_FS && !Properties.RESET_STATIC_FIELDS &&
		 * !SystemInUtil.getInstance().hasBeenUsed()) { return; }
		 */

		bd.append(METHOD_SPACE);
		bd.append("@Before \n");
		bd.append(METHOD_SPACE);
		bd.append("public void initTestCase(){ \n");

		bd.append(BLOCK_SPACE);
		bd.append(THREAD_STOPPER + ".storeCurrentThreads();\n");
		bd.append(BLOCK_SPACE);
		bd.append(THREAD_STOPPER + ".startRecordingTime();\n");

		if (Properties.REPLACE_CALLS) {
			bd.append(BLOCK_SPACE);
			bd.append(ShutdownHookHandler.class.getName() + ".getInstance().initHandler(); \n");
		}

		if (Properties.RESET_STATIC_FIELDS || wasSecurityException) {
			bd.append(BLOCK_SPACE);
			bd.append(Sandbox.class.getName() + ".goingToExecuteSUTCode(); \n");
		}

		// FIXME those should be handled in the mocked classes,eg mock for
		// java.lang.System
		if (Properties.RESET_STANDARD_STREAMS) {
			bd.append(BLOCK_SPACE);
			bd.append("systemErr = java.lang.System.err;");
			bd.append(" \n");

			bd.append(BLOCK_SPACE);
			bd.append("systemOut = java.lang.System.out;");
			bd.append(" \n");

			bd.append(BLOCK_SPACE);
			bd.append("logStream = DebugGraphics.logStream();");
			bd.append(" \n");
		}

		if (shouldResetProperties(results)) {
			bd.append(BLOCK_SPACE);
			bd.append("setSystemProperties();");
			bd.append(" \n");
		}

		/*
		 * We do not mock GUI yet, but still we need to make the JUnit tests to
		 * run in headless mode. Checking if SUT needs headless is tricky: check
		 * for headless exception is brittle if those exceptions are caught
		 * before propagating to test.
		 *
		 * TODO: These things would be handled once we mock GUI. For the time
		 * being we just always include a reset call if @Before/@After methods
		 * are generated
		 */
		if (Properties.HEADLESS_MODE) {
			bd.append(BLOCK_SPACE);
			bd.append(org.smartut.runtime.GuiSupport.class.getName() + ".setHeadless(); \n");
		}

		if (needToUseAgent()) {
			bd.append(BLOCK_SPACE);
			bd.append(org.smartut.runtime.Runtime.class.getName() + ".getInstance().resetRuntime(); \n");
			bd.append(BLOCK_SPACE);
			bd.append(InstrumentingAgent.class.getName() + ".activate(); \n");
		}

		if (SystemInUtil.getInstance().hasBeenUsed()) {
			bd.append(BLOCK_SPACE);
			bd.append(SystemInUtil.class.getName() + ".getInstance().initForTestCase(); \n");
		}

		if (JOptionPaneInputs.getInstance().hasAnyDialog()) {
			bd.append(BLOCK_SPACE);
			bd.append(JOptionPaneInputs.class.getName() + ".getInstance().initForTestCase(); \n");
		}

		bd.append(METHOD_SPACE);
		bd.append("} \n");

		bd.append("\n");
	}

	private String getResetPropertiesCommand() {
		return "java.lang.System.setProperties((java.util.Properties)" + " " + DEFAULT_PROPERTIES + ".clone());";
	}

	private void generateAfterClass(StringBuilder bd, boolean wasSecurityException, List<ExecutionResult> results) {

		if (wasSecurityException || shouldResetProperties(results)) {
			bd.append(METHOD_SPACE);
			bd.append("@AfterClass \n");
			bd.append(METHOD_SPACE);
			bd.append("public static void clearSmartUtFramework(){ \n");

			//在所有用例执行完毕，才针对于所有class进行reset
			bd.append(BLOCK_SPACE);
			bd.append("resetClasses(); \n");

			if (Properties.RESET_STATIC_FIELDS || wasSecurityException) {
				bd.append(BLOCK_SPACE);
				bd.append("Sandbox.resetDefaultSecurityManager(); \n");
			}

			if (wasSecurityException) {
				bd.append(BLOCK_SPACE);
				bd.append(EXECUTOR_SERVICE + ".shutdownNow(); \n");
			}

			if (shouldResetProperties(results)) {
				bd.append(BLOCK_SPACE);
				bd.append(getResetPropertiesCommand());
				bd.append(" \n");
			}

			bd.append(METHOD_SPACE);
			bd.append("} \n");

			bd.append("\n");
		}

	}

	private void generateSetSystemProperties(StringBuilder bd, List<ExecutionResult> results) {

		if (!Properties.REPLACE_CALLS) {
			return;
		}

		bd.append(METHOD_SPACE);
		bd.append("public static void setSystemProperties() {\n");
		bd.append(" \n");
		if (shouldResetProperties(results)) {
			/*
			 * even if we set all the properties that were read, we still need
			 * to reset everything to handle the properties that were written
			 */
			bd.append(BLOCK_SPACE);
			bd.append(getResetPropertiesCommand());
			bd.append(" \n");

			Set<String> readProperties = mergeProperties(results);
			for (String prop : readProperties) {
				String currentValue = java.lang.System.getProperty(prop);
				String escaped_prop = StringEscapeUtils.escapeJava(prop);
				if (currentValue != null) {
					String escaped_currentValue = StringEscapeUtils.escapeJava(currentValue);
					bd.append(BLOCK_SPACE);
					bd.append("java.lang.System.setProperty(\"" + escaped_prop + "\", \"" + escaped_currentValue
							+ "\"); \n");
				} else {
					/*
					 * In theory, we do not need to clear properties, as that is
					 * done with the reset to default. Avoiding doing the clear
					 * is not only good for readability (ie, less commands) but
					 * also to avoid crashes when properties are set based on
					 * SUT inputs. Eg, in classes like SassToCssBuilder in
					 * 108_liferay we ended up with hundreds of thousands set
					 * properties...
					 */
					// bd.append("java.lang.System.clearProperty(\"" +
					// escaped_prop + "\"); \n");
				}
			}
		} else {
			bd.append(BLOCK_SPACE + "/*No java.lang.System property to set*/\n");
		}

		bd.append(METHOD_SPACE);
		bd.append("}\n");

	}

	private void generateBeforeClass(StringBuilder bd, boolean wasSecurityException, List<ExecutionResult> results) {

		if (!wasSecurityException && !needToUseAgent()) {
			return;
		}

		bd.append("\n");
		bd.append(METHOD_SPACE);
		bd.append("@BeforeClass \n");
		bd.append(METHOD_SPACE);
		bd.append("public static void initSmartUtFramework() { \n");

		// FIXME: This is just commented out for experiments
		// bd.append("org.smartut.utils.LoggingUtils.setLoggingForJUnit();
		// \n");

		bd.append(BLOCK_SPACE);
		bd.append("" + RuntimeSettings.class.getName() + ".className = \"" + Properties.TARGET_CLASS + "\"; \n");

		bd.append(BLOCK_SPACE);
		bd.append("" + GuiSupport.class.getName() + ".initialize(); \n");

		if (Properties.REPLACE_CALLS) {
			bd.append(BLOCK_SPACE);
			bd.append("" + RuntimeSettings.class.getName() + ".maxNumberOfThreads = " + Properties.MAX_STARTED_THREADS
					+ "; \n");
		}

		bd.append(BLOCK_SPACE);
		bd.append("" + RuntimeSettings.class.getName() + ".maxNumberOfIterationsPerLoop = "
				+ Properties.MAX_LOOP_ITERATIONS + "; \n");

		if (Properties.REPLACE_SYSTEM_IN) {
			bd.append(BLOCK_SPACE);
			bd.append(RuntimeSettings.class.getName() + ".mockSystemIn = true; \n");
		}

		if (Properties.REPLACE_GUI) {
			bd.append(BLOCK_SPACE);
			bd.append(RuntimeSettings.class.getName() + ".mockGUI = true; \n");
		}

		if (Properties.RESET_STATIC_FIELDS || wasSecurityException) {
			// need to setup the Sandbox mode
			bd.append(BLOCK_SPACE);
			bd.append(RuntimeSettings.class.getName() + ".sandboxMode = " + Sandbox.SandboxMode.class.getCanonicalName()
					+ "." + Properties.SANDBOX_MODE + "; \n");

			bd.append(BLOCK_SPACE);
			bd.append(Sandbox.class.getName() + ".initializeSecurityManagerForSUT(); \n");
		}

		if (wasSecurityException) {
			bd.append(BLOCK_SPACE);
			bd.append(EXECUTOR_SERVICE + " = Executors.newCachedThreadPool(); \n");
		}

		if (Properties.RESET_STATIC_FIELDS && Properties.REPLACE_CALLS) {
			bd.append(BLOCK_SPACE);
			bd.append(JDKClassResetter.class.getName() + ".init();\n");
			bd.append(BLOCK_SPACE);
			bd.append("setSystemProperties();\n");
			bd.append(BLOCK_SPACE);
			bd.append(InitializingListener.INITIALIZE_CLASSES_METHOD + "();" + "\n");
		}

		if (needToUseAgent()) {
			bd.append(BLOCK_SPACE);
			bd.append(org.smartut.runtime.Runtime.class.getName() + ".getInstance().resetRuntime(); \n");
		} else {
			// it is done inside Runtime, but, if that is not called, we need an
			// explicit call here
			bd.append(BLOCK_SPACE);
			bd.append(LoopCounter.class.getName() + ".getInstance().reset(); \n");
		}

		if(doesUseMocks(results)) {
			bd.append(BLOCK_SPACE);
			bd.append("try { initMocksToAvoidTimeoutsInTheTests(); } catch(ClassNotFoundException e) {} \n");
		}
		bd.append(METHOD_SPACE);
		bd.append("} \n");

		bd.append("\n");
	}

	private void generateFields(StringBuilder bd, boolean wasSecurityException, List<ExecutionResult> results) {

		if (Properties.RESET_STANDARD_STREAMS) {
			bd.append(METHOD_SPACE);
			bd.append("private PrintStream systemOut = null;" + '\n');

			bd.append(METHOD_SPACE);
			bd.append("private PrintStream systemErr = null;" + '\n');

			bd.append(METHOD_SPACE);
			bd.append("private PrintStream logStream = null;" + '\n');
		}

		if (wasSecurityException) {
			bd.append(METHOD_SPACE);
			bd.append("protected static ExecutorService " + EXECUTOR_SERVICE + "; \n");

			bd.append("\n");
		}

		if (shouldResetProperties(results)) {
			/*
			 * some System properties were read/written. so, let's be sure we ll
			 * have the same properties in the generated JUnit file, regardless
			 * of where it will be executed (eg on a remote CI server). This is
			 * essential, as generated assertions might depend on those
			 * properties
			 */
			bd.append(METHOD_SPACE);
			bd.append("private static final java.util.Properties " + DEFAULT_PROPERTIES);
			bd.append(" = (java.util.Properties) java.lang.System.getProperties().clone(); \n");

			bd.append("\n");
		}

		bd.append(METHOD_SPACE);
		bd.append("private " + ThreadStopper.class.getName() + " " + THREAD_STOPPER + " = ");
		bd.append(" new " + ThreadStopper.class.getName() + " (");
		bd.append("" + KillSwitchHandler.class.getName() + ".getInstance(), ");
		bd.append("" + Properties.TIMEOUT + "");
		// this shouldn't appear among the threads in the generated tests
		// threadsToIgnore.add(TestCaseExecutor.TEST_EXECUTION_THREAD);
		Set<String> threadsToIgnore = new LinkedHashSet<>(Arrays.asList(Properties.IGNORE_THREADS));
		for (String s : threadsToIgnore) {
			bd.append(", " + s);
		}
		bd.append(");\n\n");
	}
}

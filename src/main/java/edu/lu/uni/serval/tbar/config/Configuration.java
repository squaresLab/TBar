package edu.lu.uni.serval.tbar.config;

public class Configuration {

	public static String failedTestCasesFilePath = "FailedTestCases/";
	public static String faultLocalizationMetric = "Ochiai";
	public static String outputPath = "OUTPUT/";
	public static String bugDataPath = "";
	public static String defects4j_home = "";
	public static final String TEMP_FILES_PATH = ".temp/";
	public static final long SHELL_RUN_TIMEOUT = 300L;
	public static final long TEST_SHELL_RUN_TIMEOUT = 600L;
	public static boolean clearTestCache = false;
	public static boolean storePatchJson = false;
	public static boolean compileOnly = false;
	public static boolean recordAllPatches = false;
	public static String testcache = "testcache.ser";
}

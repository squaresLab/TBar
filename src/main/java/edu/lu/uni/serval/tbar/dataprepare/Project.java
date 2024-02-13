package edu.lu.uni.serval.tbar.dataprepare;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.lu.uni.serval.tbar.code.analyser.JavaCodeFileParser;
import edu.lu.uni.serval.tbar.config.Configuration;
import edu.lu.uni.serval.tbar.context.Dictionary;
import edu.lu.uni.serval.tbar.faultloc.SuspCodeNode;
import edu.lu.uni.serval.tbar.fixers.AbstractFixer;
import edu.lu.uni.serval.tbar.fixers.FixStatus;
import edu.lu.uni.serval.tbar.utils.FileHelper;
import edu.lu.uni.serval.tbar.utils.PathUtils;
import edu.lu.uni.serval.tbar.utils.ShellUtils;
import edu.lu.uni.serval.tbar.utils.TestUtils;

public abstract class Project {
    protected static Logger log = LoggerFactory.getLogger(Project.class);

    protected final String projectName;
    private final int bugNum;
    protected DataPreparer dp; // The needed data of buggy program for compiling and testing.
    protected final String fullPathToBuggyProject;
    private int failingTestCount; // the old "minErrorTest"
    // All specific failed test cases after testing the buggy project with defects4j
    // command in Java code before fixing.

    // OK Claire gets it.  The trick is to figure out how many you originally failed, i.e., if partial repair is possible. 
    private List<String> failedTestCasesList = new ArrayList<>();
    private List<String> fakeFailedTestCasesList = new ArrayList<>();
    private List<String> failedTestCasesStrList = new ArrayList<>(); // FIXME: I would like to not have 3 of these

    protected String failedTestCaseClasses;
	protected List<String> failedTestStrList = new ArrayList<>();

    public String getProjectName() { return this.projectName; }
    public String getFullPathToBuggyProject() { return this.fullPathToBuggyProject; }
    public String getSrcPath() { return dp.srcPath; }

    public boolean isValid() {
        return dp.validPaths;
    }
    
    public Project(String name, int num, String path) {
        projectName = name;
        bugNum = num;
        this.dp = new DataPreparer(path);
        dp.prepareData(name);

        this.fullPathToBuggyProject = path + "/" + name;

        TestUtils.checkout(fullPathToBuggyProject);
        // if (FileHelper.getAllFiles(fullBuggyProjectPath +
        // PathUtils.getSrcPath(buggyProject).get(0), ".class").isEmpty()) {
        TestUtils.compileProjectWithDefects4j(fullPathToBuggyProject, Configuration.defects4jPath);
        // }
        failingTestCount = TestUtils.getFailTestNumInProject(fullPathToBuggyProject, Configuration.defects4jPath,
                failedTestCasesList);
        if (failingTestCount == Integer.MAX_VALUE) {
            TestUtils.compileProjectWithDefects4j(fullPathToBuggyProject, Configuration.defects4jPath);
            failingTestCount = TestUtils.getFailTestNumInProject(fullPathToBuggyProject, Configuration.defects4jPath,
                    failedTestCasesList);
        }
        
        log.info(Configuration.buggyProject + " Number of Failed Tests: " + this.failingTestCount);
		readPreviouslyFailedTestCases();
    }

    public DataPreparer getDataPreparer() {
        return this.dp;
    }

    private void readPreviouslyFailedTestCases() {
		String[] failedTestCases = FileHelper.readFile(Configuration.failedTestCasesFilePath + "/" + this.projectName + ".txt").split("\n");
		List<String> failedTestCasesList = new ArrayList<>();
		List<String> failed = new ArrayList<>();
		for (int index = 1, length = failedTestCases.length; index < length; index ++) {
			// - org.jfree.data.general.junit.DatasetUtilitiesTests::testBug2849731_2
			String failedTestCase = failedTestCases[index].trim();
			failed.add(failedTestCase);
			failedTestCase = failedTestCase.substring(failedTestCase.indexOf("-") + 1).trim();
			failedTestCasesStrList.add(failedTestCase);
			int colonIndex = failedTestCase.indexOf("::");
			if (colonIndex > 0) {
				failedTestCase = failedTestCase.substring(0, colonIndex);
			}
			if (!failedTestCasesList.contains(failedTestCase)) {
				this.failedTestCaseClasses += failedTestCase + " ";
				failedTestCasesList.add(failedTestCase);
			}
		}
		
		List<String> tempFailed = new ArrayList<>();
		tempFailed.addAll(this.failedTestStrList);
		tempFailed.removeAll(failed);
		// FIXME: Using defects4j command in Java code may generate some new failed-passing test cases.
		// We call them as fake failed-passing test cases.
		this.fakeFailedTestCasesList.addAll(tempFailed);
	}


    // returns true/false corresponding to successful/unsuccessful compilation.
    public boolean compile(SuspCodeNode scn) {
        try {// Compile patched file.
            ShellUtils.shellRun(Arrays.asList("javac -Xlint:unchecked -source 1.7 -target 1.7 -cp "
                    + PathUtils.buildCompileClassPath(Arrays.asList(PathUtils.getJunitPath()), dp.classPath,
                            dp.testClassPath)
                    + " -d " + dp.classPath + " " + scn.targetJavaFile.getAbsolutePath()), Configuration.buggyProject,
                    1);
        } catch (IOException e) {
            log.debug(Configuration.buggyProject + " ---Fixer: fix fail because of javac exception! ");
            return false;
        }
        return true;
    }


    protected abstract String subtest();

    // run project tests
    public FixStatus test() {
        String results = this.subtest();
        if (results.isEmpty() || results.contains("java.lang.NoClassDefFoundError")) 
            return FixStatus.FAILURE; // FIXME: consider error handling here, I'm concerned about the classdef error check
        // System.err.println(scn.suspiciousJavaFile + "@" + scn.buggyLine);
        // System.err.println("Bug: " + buggyCode);
        // System.err.println("Patch: " + patchCode);
        if (!results.contains("java.lang.NoClassDefFoundError")) {
            List<String> tempFailedTestCases = TestUtils.readTestResults(results);
            tempFailedTestCases.retainAll(this.fakeFailedTestCasesList); // this line makes no sense to me
            if (!tempFailedTestCases.isEmpty()) {
                if (this.failedTestCasesStrList.size() == 1) return FixStatus.FAILURE;

                // Might be partially fixed.
                tempFailedTestCases.removeAll(this.failedTestCasesStrList);
                if (!tempFailedTestCases.isEmpty()) return FixStatus.FAILURE; // Generated new bugs.
            }
        }
        List<String> failedTestsAfterFix = new ArrayList<>();
        int errorTestAfterFix = TestUtils.getFailTestNumInProject(this.fullPathToBuggyProject, Configuration.defects4jPath, failedTestsAfterFix);
        failedTestsAfterFix.removeAll(this.fakeFailedTestCasesList);
        
        // if (errorTestAfterFix < minErrorTest) {
        List<String> tmpFailedTestsAfterFix = new ArrayList<>();
        tmpFailedTestsAfterFix.addAll(failedTestsAfterFix);
        tmpFailedTestsAfterFix.removeAll(this.failedTestStrList);
        if (tmpFailedTestsAfterFix.size() > 0) { // Generate new bugs.
            log.debug(this.projectName + " ---Generated new bugs: " + tmpFailedTestsAfterFix.size());
            return FixStatus.FAILURE;
        }
        
        if (errorTestAfterFix == 0 || failedTestsAfterFix.isEmpty()) { 
            return FixStatus.SUCCESS;
        }
        // otherwise, should be a partial fix...there was a weird set of conditions in AbstractFixer here that I absolutely don't understand where a partial is secretly a full fix, as follows: 
        // FIXME: figure this out??
        /* if (minErrorTestAfterFix == 0 || errorTestAfterFix <= minErrorTestAfterFix) {
                minErrorTestAfterFix = errorTestAfterFix;
                fixedStatus = FixStatus.PARTIAL;
                minErrorTest_ = minErrorTest_ - (minErrorTest - errorTestAfterFix);
                if (minErrorTest_ <= 0) {
                    fixedStatus = FixStatus.SUCCESS;
                    minErrorTest = 0;
                }
                log.info("Partially Succeeded to fix the bug " + buggyProject + "====================");
                */ 
        return FixStatus.PARTIAL;
    }

    public void cleanup(SuspCodeNode scn) {
        try {
            scn.targetJavaFile.delete();
            scn.targetClassFile.delete();
            Files.copy(scn.javaBackup.toPath(), scn.targetJavaFile.toPath());
            Files.copy(scn.classBackup.toPath(), scn.targetClassFile.toPath());
        } catch (IOException e1) {
            e1.printStackTrace();
        }
    }
    
    protected Dictionary dic = null;

    @SuppressWarnings("unused")
	private void createDictionary() {
		dic = new Dictionary();
		List<File> javaFiles = FileHelper.getAllFiles(dp.srcPath, ".java");
		for (File javaFile : javaFiles) {
			JavaCodeFileParser jcfp = new JavaCodeFileParser(javaFile);
			dic.setAllFields(jcfp.fields);
			dic.setImportedDependencies(jcfp.importMaps);
			dic.setMethods(jcfp.methods);
			dic.setSuperClasses(jcfp.superClassNames);
		}
	}
}

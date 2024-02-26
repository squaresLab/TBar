package edu.lu.uni.serval.tbar.fixers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.FileNotFoundException;
import java.io.Reader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.nio.file.Paths;

import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.lu.uni.serval.tbar.faultloc.AbstractFaultLoc;
import edu.lu.uni.serval.tbar.code.analyser.JavaCodeFileParser;
import edu.lu.uni.serval.tbar.config.Configuration;
import edu.lu.uni.serval.tbar.context.Dictionary;
import edu.lu.uni.serval.tbar.dataprepare.DataPreparer;
import edu.lu.uni.serval.tbar.faultloc.SuspCodeNode;
import edu.lu.uni.serval.tbar.info.Patch;
import edu.lu.uni.serval.tbar.utils.FileHelper;
import edu.lu.uni.serval.tbar.utils.PathUtils;
import edu.lu.uni.serval.tbar.utils.ShellUtils;
import edu.lu.uni.serval.tbar.utils.TestUtils;

/**
 * Abstract Fixer.
 * 
 * @author kui.liu
 *
 */
public abstract class AbstractFixer {
	
	private static Logger log = LoggerFactory.getLogger(AbstractFixer.class);
	
	public abstract FixStatus fixProcess();
	protected String path = "";
	protected String buggyProject = "";     // The buggy project name.
	private int numberFailingTests;                // Number of failed test cases before fixing.
	public int minErrorTest_;
	protected int minErrorTestAfterFix = 0; // Number of required ?? FIXME failed test cases after fixing
	protected String fullBuggyProjectPath;  // The full path of the local buggy project.
	public String outputPath = "";          // Output path for the generated patches.
	protected DataPreparer dp;              // The needed data of buggy program for compiling and testing.
	protected AbstractFaultLoc faultloc = null;

	private List<String> failedTestCaseClasses = new ArrayList<>(); // Classes of the failed test cases before fixing. This is the class path, no hyphen
	// All specific failed test cases after testing the buggy project with defects4j command in Java code before fixing.
	protected Map<String,List<String>> failedTestCases = new HashMap<>();
	private List<String> fakeFailedTestCasesList = new ArrayList<>(); // also no hyphen
	
	public String dataType = "";
	protected int patchId = 0;
	protected int comparablePatches = 0;
//	private TimeLine timeLine;
	protected Dictionary dic = null;
	
	public boolean isTestFixPatterns = false;

	public DataPreparer getDataPreparer() { return this.dp; }
	public void setFaultLoc(AbstractFaultLoc fl) { this.faultloc = fl; }
	public AbstractFixer(String path, String projectName, int bugId) {
		this.path = path;
		this.buggyProject = projectName + "_" + bugId;
		fullBuggyProjectPath = path + "/" + buggyProject;
//		int compileResult = TestUtils.compileProjectWithDefects4j(fullBuggyProjectPath, this.defects4jPath);
//      if (compileResult == 1) {
//      	log.debug(buggyProject + " ---Fixer: fix fail because of compile fail! ");
//      }
		
		TestUtils.checkout(this.fullBuggyProjectPath);
//		if (FileHelper.getAllFiles(fullBuggyProjectPath + PathUtils.getSrcPath(buggyProject).get(0), ".class").isEmpty()) {
			TestUtils.compileProjectWithDefects4j(fullBuggyProjectPath);
//		}
		List<String> failedTestsFromD4J = TestUtils.getFailedTestsFromD4J(fullBuggyProjectPath);
	
		numberFailingTests = failedTestsFromD4J.size();
		if (numberFailingTests == 0) {
			TestUtils.compileProjectWithDefects4j(fullBuggyProjectPath);
			failedTestsFromD4J = TestUtils.getFailedTestsFromD4J(fullBuggyProjectPath);
			numberFailingTests = failedTestsFromD4J.size();
		}
		minErrorTest_ = numberFailingTests;
		log.info(buggyProject + " Failed Tests: " + this.numberFailingTests);
		
		// Read paths of the buggy project.
		this.dp = new DataPreparer(path);
		dp.prepareData(buggyProject);
		
		readPreviouslyFailedTestCases(failedTestsFromD4J);
		AbstractFixer.deserializeTestCache();

		//		createDictionary();
	}

	public int numberInitiallyFailingTests() { return numberFailingTests; }

	private void readPreviouslyFailedTestCases(List<String> failedTestsFromD4J) {
		// the failedTestsFromD4J are class::testname
		
		String[] failedTestCasesFromFile = FileHelper.readFile(Configuration.failedTestCasesFilePath + "/" + this.buggyProject + ".txt").split("\n");
		List<String> failed = new ArrayList<>();
		for (int index = 1, length = failedTestCasesFromFile.length; index < length; index ++) {
			// - org.jfree.data.general.junit.DatasetUtilitiesTests::testBug2849731_2
			failed.add(TestUtils.cleanTestName(failedTestCasesFromFile[index]));
		}
		// I want fake failed to only have the tests that failed in d4j but not in the file.
			// Using defects4j command in Java code may generate some new failed-passing test cases.
		// We call them as fake failed-passing test cases.
		List<String> tempFailed = new ArrayList<>();
		tempFailed.addAll(failedTestsFromD4J);
		tempFailed.removeAll(failed);
		this.fakeFailedTestCasesList.addAll(tempFailed);

		for(String test : failed) {
			String className = "";
			int colonIndex = test.indexOf("::");
			if (colonIndex > 0) {
				className = test.substring(0, colonIndex); // now it's just the class name, not the individual test
			} 
			if(!this.failedTestCaseClasses.contains(className)) this.failedTestCaseClasses.add(className);
			List<String> classTests = null;
			if(this.failedTestCases.containsKey(className)) {
				classTests = this.failedTestCases.get(className);
			} else {
				classTests = new ArrayList<String>();
				this.failedTestCases.put(className,classTests);
			}
			classTests.add(className);
		}
	}

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

	protected List<Patch> triedPatchCandidates = new ArrayList<>();
	protected static HashMap<String,FixStatus> patchCache = new HashMap<>();

	// FIXME: add some kind of runtime hook to serialize if the process gets killed prematurely.
	public static void serializeTestCache() {
		try {
			FileOutputStream fos = new FileOutputStream(Configuration.testcache); 
			ObjectOutputStream oos = new ObjectOutputStream(fos);
			oos.writeObject(AbstractFixer.patchCache);
			oos.close();
			fos.close();
			log.info("Serialized test cache to file " + Configuration.testcache);
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}

	public static void deserializeTestCache(){
		File fl = new File(Configuration.testcache); 
		HashMap<String, FixStatus> cache = null; 
		if(fl.isFile() && !Configuration.clearTestCache){
			System.out.println("DESERIALIZING: " + Configuration.testcache);
			try
			{
				FileInputStream fis = new FileInputStream(Configuration.testcache); 
				ObjectInputStream ois = new ObjectInputStream(fis);
				cache = (HashMap) ois.readObject();
				ois.close();
				fis.close();
			}catch(IOException ioe)
			{
				ioe.printStackTrace();
			}catch(ClassNotFoundException c)
			{
				System.out.println("Class not found");
				c.printStackTrace();
			}
			System.out.println("Deserialized testcache HashMap");
		} else {
			cache = new HashMap<String, FixStatus>();
		}
		//System.out.println("hashmap is = " + testCache.entrySet().size() + "  " + testCache.toString());
		patchCache.putAll(cache);
	}
	
	protected boolean compile(SuspCodeNode scn) {
		try {// Compile patched file.
			ShellUtils.shellRun(Arrays.asList("javac -Xlint:unchecked -source 1.7 -target 1.7 -cp "
					+ PathUtils.buildCompileClassPath(Arrays.asList(PathUtils.getJunitPath()), dp.classPath, dp.testClassPath)
					+ " -d " + dp.classPath + " " + scn.targetJavaFile.getAbsolutePath()), buggyProject, 1);
		} catch (IOException e) {
			log.debug(buggyProject + " ---Fixer: fix fail because of javac exception! ");
			return false;
		}
		if (!scn.targetClassFile.exists()) { // fail to compile
			int results = (this.buggyProject.startsWith("Mockito") || this.buggyProject.startsWith("Closure") || this.buggyProject.startsWith("Time")) ? TestUtils.compileProjectWithDefects4j(fullBuggyProjectPath) : 1;
			if (results == 1) {
				log.debug(buggyProject + " ---Fixer: fix fail because of failed compiling! ");
				return false;
			}
		}
		return true;
	}

	// returns false in case of obvious failures, true otherwise, yes this needs additional refactoring.
	private boolean runInitiallyFailingTests() {
		for(String failedClass : this.failedTestCaseClasses) {
			List<String> prevFailedTests = this.failedTestCases.get(failedClass); // if this failed things have gone terrifically awry
			try {
			String results = ShellUtils.shellRun(Arrays.asList("java -cp "
					+ PathUtils.buildTestClassPath(dp.classPath, dp.testClassPath)
					+ " org.junit.runner.JUnitCore " + failedClass), buggyProject, 2);
			if (results.isEmpty()) {
				return false;
			} else {
				if (!results.contains("java.lang.NoClassDefFoundError")) {
					List<String> tempFailedTestCases = readTestResults(results);
					tempFailedTestCases.removeAll(this.fakeFailedTestCasesList);
					if(tempFailedTestCases.size() < prevFailedTests.size()) {
						tempFailedTestCases.removeAll(prevFailedTests);
						if(tempFailedTestCases.size() > 0) return false; // have generated new bugs
						else continue; // could be a partial fix
				} else return false; 
				}
			}
		} catch (IOException e) {
			if (!(this.buggyProject.startsWith("Mockito") || this.buggyProject.startsWith("Closure") || this.buggyProject.startsWith("Time"))) {
				log.debug(buggyProject + " ---Fixer: fix fail because of failed passing previously failed test cases! ");
				return false;
			}
		}
	}
		return true;
	}

	private void postPatchAttemptCleanup(FixStatus status, SuspCodeNode scn, Patch patch, String buggyCode, String patchCode, String patchedFile) {
		if(!Configuration.compileOnly || (Configuration.compileOnly && status == FixStatus.NOCOMPILE))
			patchCache.put(this.buggyProject + patchedFile,status);

		if((Configuration.recordAllPatches && status != FixStatus.NOCOMPILE) ||
			(status == FixStatus.PARTIAL) || (status == FixStatus.SUCCESS)) {
			// output 
			String patchStr = TestUtils.readPatch(this.fullBuggyProjectPath);
			String outputFolder = status == FixStatus.SUCCESS ? "/FixedBugs/" : "/PartiallyFixedBugs/";
			
			
			if (patchStr == null || !patchStr.startsWith("diff")) {
				FileHelper.outputToFile(Configuration.outputPath + this.dataType + outputFolder + buggyProject + "/Patch_" + patchId + "_" + comparablePatches + ".txt",
						"//**********************************************************\n//" + scn.suspiciousJavaFile + " ------ " + scn.buggyLine
						+ "\n//**********************************************************\n"
						+ "===Buggy Code===\n" + buggyCode + "\n\n===Patch Code===\n" + patchCode, false);
			} else {
				FileHelper.outputToFile(Configuration.outputPath + this.dataType + outputFolder + buggyProject + "/Patch_" + patchId + "_" + comparablePatches + ".txt", patchStr, false);
			}
			if (!isTestFixPatterns) {
				this.numberFailingTests = 0;
			}
		}
	}


	protected FixStatus testGeneratedPatches(List<Patch> patchCandidates, List<Patch> patchEntropyCandidates, SuspCodeNode scn) {
		// Testing generated patches.
		FixStatus fixedStatus = FixStatus.FAILURE;
		if (!Configuration.patchRankFile.isEmpty() && !patchEntropyCandidates.isEmpty()) {
			patchCandidates.clear();
			patchCandidates = new ArrayList<>(patchEntropyCandidates);
		}
		System.out.println("Testing " + patchCandidates.size() + " patches");
		for (Patch patch : patchCandidates) {
			System.out.println("Printing out patch string " + patch.getFixedCodeStr1());
			try{
				if (this.triedPatchCandidates.contains(patch)) continue;
			} catch (NullPointerException e) {
				continue;
			}
			patchId++;
			patch.buggyFileName = scn.suspiciousJavaFile;
			String patchedFile = addPatchCodeToFile(scn, patch);// Insert the patch.
			String buggyCode = patch.getBuggyCodeStr();
			String patchCode = patch.getFixedCodeStr1();

			
			if(patchCache.containsKey(this.buggyProject + patchedFile) && Configuration.useTestCache) {
				fixedStatus = patchCache.get(this.buggyProject + patchedFile);
				if(fixedStatus == FixStatus.SUCCESS){
					postPatchAttemptCleanup(fixedStatus, scn, patch, buggyCode, patchCode, patchedFile);
					return FixStatus.SUCCESS;
				}
				continue;
			}

			
			if (patchId > 10000) return FixStatus.FAILURE;
			this.triedPatchCandidates.add(patch);
			
			
			if ("===StringIndexOutOfBoundsException===".equals(buggyCode)) continue;
			scn.targetClassFile.delete();

			log.debug("Compiling");
            Boolean compiled = this.compile(scn);

            if(Configuration.compileOnly) {
				postPatchAttemptCleanup(compiled ? FixStatus.SUCCESS : FixStatus.NOCOMPILE, scn, patch, buggyCode, patchCode, patchedFile);
				continue;
			}

            if(!compiled) {
				log.debug(buggyProject + " ---Fixer: fix fail because of failed compiling! ");
				postPatchAttemptCleanup(FixStatus.NOCOMPILE, scn, patch, buggyCode, patchCode, patchedFile);
				continue;
			} 
			log.debug("Finished compiling.");
			
			comparablePatches++;
			log.debug("Test previously failed test cases.");
			if(!runInitiallyFailingTests()) {
				postPatchAttemptCleanup(FixStatus.FAILURE, scn, patch, buggyCode, patchCode, patchedFile);
                continue;
			}
			List<String> failedTestsAfterFix = TestUtils.getFailedTestsFromD4J(fullBuggyProjectPath);
			failedTestsAfterFix.removeAll(this.fakeFailedTestCasesList);
			int errorTestAfterFix = failedTestsAfterFix.size();
			if (errorTestAfterFix < numberFailingTests) {
			List<String> tmpFailedTestsAfterFix = new ArrayList<>();
			tmpFailedTestsAfterFix.addAll(failedTestsAfterFix);

			List<String> originalFailedTests = new ArrayList<String>();
			// FIXME: this is kind of gross to do here
			for(Map.Entry<String,List<String>> entry : this.failedTestCases.entrySet()) {
				for(String testName : entry.getValue()) {
					originalFailedTests.add(testName);
				}
			}

			tmpFailedTestsAfterFix.removeAll(originalFailedTests);
			if (tmpFailedTestsAfterFix.size() > 0) { // Generate new bugs.
				log.debug(buggyProject + " ---Generated new bugs: " + tmpFailedTestsAfterFix.size());
				postPatchAttemptCleanup(FixStatus.FAILURE, scn, patch, buggyCode, patchCode, patchedFile);
				continue;
			}
			
			// Output the generated patch.
			if (errorTestAfterFix == 0) {
				fixedStatus = FixStatus.SUCCESS;
				log.info("Succeeded to fix the bug " + buggyProject + "====================");
				log.info("Final patch number: " + patchId);
			} else if (minErrorTestAfterFix == 0 || errorTestAfterFix <= minErrorTestAfterFix) {
				log.info("Final patch number: " + patchId);
				minErrorTestAfterFix = errorTestAfterFix;
				fixedStatus = FixStatus.PARTIAL;
				log.info("Partially Succeeded to fix the bug " + buggyProject + "====================");
				minErrorTest_ = minErrorTest_ - (numberFailingTests - errorTestAfterFix);
				if (minErrorTest_ <= 0) {
					log.info("Succeeded to fix the bug " + buggyProject + "====================");
					fixedStatus = FixStatus.SUCCESS;
					numberFailingTests = 0;
				}
			}

			
			
			postPatchAttemptCleanup(fixedStatus, scn, patch, buggyCode, patchCode, patchedFile);
			if(fixedStatus == FixStatus.SUCCESS || fixedStatus == FixStatus.PARTIAL) {
				break;
			}
			}
		}

		try {
			scn.targetJavaFile.delete();
			scn.targetClassFile.delete();
			Files.copy(scn.javaBackup.toPath(), scn.targetJavaFile.toPath());
			Files.copy(scn.classBackup.toPath(), scn.targetClassFile.toPath());
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		return fixedStatus;
	}
	
	private List<String> readTestResults(String results) {
		List<String> failedTeatCases = new ArrayList<>();
		String[] testResults = results.split("\n");
		for (String testResult : testResults) {
			if (testResult.isEmpty()) continue;
			
			if (NumberUtils.isDigits(testResult.substring(0, 1))) {
				int index = testResult.indexOf(") ");
				if (index <= 0) continue;
				testResult = testResult.substring(index + 1, testResult.length() - 1).trim();
				int indexOfLeftParenthesis = testResult.indexOf("(");
				if (indexOfLeftParenthesis < 0) {
					System.err.println(testResult);
					continue;
				}
				String testCase = testResult.substring(0, indexOfLeftParenthesis);
				String testClass = testResult.substring(indexOfLeftParenthesis + 1);
				failedTeatCases.add(testClass + "::" + testCase);
			}
		}
		return failedTeatCases;
	}

	private String addPatchCodeToFile(SuspCodeNode scn, Patch patch) {
        String javaCode = FileHelper.readFile(scn.javaBackup);
        
		String fixedCodeStr1 = patch.getFixedCodeStr1();
		String fixedCodeStr2 = patch.getFixedCodeStr2();
		int exactBuggyCodeStartPos = patch.getBuggyCodeStartPos();
		int exactBuggyCodeEndPos = patch.getBuggyCodeEndPos();
		System.out.println("tofile exact start " + exactBuggyCodeStartPos + " end " + exactBuggyCodeEndPos);
		String patchCode = fixedCodeStr1;
		boolean needBuggyCode = false;
		String patchedJavaFile = "";
		if (exactBuggyCodeEndPos > exactBuggyCodeStartPos) {
			if ("MOVE-BUGGY-STATEMENT".equals(fixedCodeStr2)) {
				// move statement position.
			} else if (exactBuggyCodeStartPos != -1 && exactBuggyCodeStartPos < scn.startPos) {
				// Remove the buggy method declaration.
			} else {
				needBuggyCode = true;
				if (exactBuggyCodeStartPos == 0) {
					// Insert the missing override method, the buggy node is TypeDeclaration.
					int pos = scn.suspCodeAstNode.getPos() + scn.suspCodeAstNode.getLength() - 1;
					for (int i = pos; i >= 0; i --) {
						if (javaCode.charAt(i) == '}') {
							exactBuggyCodeStartPos = i;
							exactBuggyCodeEndPos = i + 1;
							break;
						}
					}
				} else if (exactBuggyCodeStartPos == -1 ) {
					// Insert generated patch code before the buggy code.
					exactBuggyCodeStartPos = scn.startPos;
					exactBuggyCodeEndPos = scn.endPos;
				} else {
					// Insert a block-held statement to surround the buggy code
				}
			}
		} else if (exactBuggyCodeStartPos == -1 && exactBuggyCodeEndPos == -1) {
			// Replace the buggy code with the generated patch code.
			exactBuggyCodeStartPos = scn.startPos;
			exactBuggyCodeEndPos = scn.endPos;
		} else if (exactBuggyCodeStartPos == exactBuggyCodeEndPos) {
			// Remove buggy variable declaration statement.
			exactBuggyCodeStartPos = scn.startPos;
		}
		
		patch.setBuggyCodeStartPos(exactBuggyCodeStartPos);
		patch.setBuggyCodeEndPos(exactBuggyCodeEndPos);
        String buggyCode;
		try {
			buggyCode = javaCode.substring(exactBuggyCodeStartPos, exactBuggyCodeEndPos);
			if (needBuggyCode) {
	        	patchCode += buggyCode;
	        	if (fixedCodeStr2 != null) {
	        		patchCode += fixedCodeStr2;
	        	}
	        }
			
			File newFile = new File(scn.targetJavaFile.getAbsolutePath() + ".temp");
	        patchedJavaFile = javaCode.substring(0, exactBuggyCodeStartPos) + patchCode + javaCode.substring(exactBuggyCodeEndPos);
	        FileHelper.outputToFile(newFile, patchedJavaFile, false);
	        newFile.renameTo(scn.targetJavaFile);

			// System.out.println("patched java file " + patchedJavaFile);
			
			if (Configuration.storePatchJson){
				File patch_storage = new File(Paths.get("").toAbsolutePath().toString() + "/stored_patches");
				patch_storage.mkdir();
				String patchDir = Paths.get("").toAbsolutePath().toString() + "/stored_patches/" + buggyProject;
				File toEntropyDir = new File(patchDir);
				toEntropyDir.mkdir();
				String patchPath = patchDir + "/" + patchId + ".json";
				JSONObject jsonObject = new JSONObject();
				
				jsonObject.put("patchID", patchId);
				jsonObject.put("exactBuggyCodeStartPos", exactBuggyCodeStartPos);
				jsonObject.put("exactBuggyCodeEndPos", exactBuggyCodeEndPos);
				jsonObject.put("patchCode1", fixedCodeStr1);
				jsonObject.put("patchCode2", fixedCodeStr2);
				try {
					FileWriter file = new FileWriter(patchDir + "/" + patchId + ".json");
					file.write(jsonObject.toJSONString());
					file.close();
				 } catch (IOException e) {
					e.printStackTrace();
				 }
			}

		} catch (StringIndexOutOfBoundsException e) {
			log.debug(exactBuggyCodeStartPos + " ==> " + exactBuggyCodeEndPos + " : " + javaCode.length());
			e.printStackTrace();
			buggyCode = "===StringIndexOutOfBoundsException===";
		}
        
        patch.setBuggyCodeStr(buggyCode);
        patch.setFixedCodeStr1(patchCode);
		return patchedJavaFile;
	}
	
}

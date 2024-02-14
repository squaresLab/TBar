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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.nio.file.Paths;
import org.json.simple.JSONObject;

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
import junit.framework.TestCase;

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
	public int minErrorTest;                // Number of failed test cases before fixing.
	public int minErrorTest_;
	protected int minErrorTestAfterFix = 0; // Number of failed test cases after fixing
	protected String fullBuggyProjectPath;  // The full path of the local buggy project.
	public String outputPath = "";          // Output path for the generated patches.
	protected DataPreparer dp;              // The needed data of buggy program for compiling and testing.
	protected AbstractFaultLoc faultloc = null;
	public boolean compileOnly = false;
	public boolean recordAllPatches = false;
	public boolean storePatchJson = false;

	private String failedTestCaseClasses = ""; // Classes of the failed test cases before fixing.
	// All specific failed test cases after testing the buggy project with defects4j command in Java code before fixing.
	protected List<String> failedTestStrList = new ArrayList<>();
	// All specific failed test cases after testing the buggy project with defects4j command in terminal before fixing.
	protected List<String> failedTestCasesStrList = new ArrayList<>();
	// The failed test cases after running defects4j command in Java code but not in terminal.
	private List<String> fakeFailedTestCasesList = new ArrayList<>();
	
	public FixStatus fixedStatus = FixStatus.FAILURE;
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
		minErrorTest = TestUtils.getFailTestNumInProject(fullBuggyProjectPath, failedTestStrList);
		if (minErrorTest == Integer.MAX_VALUE) {
			TestUtils.compileProjectWithDefects4j(fullBuggyProjectPath);
			minErrorTest = TestUtils.getFailTestNumInProject(fullBuggyProjectPath, failedTestStrList);
		}
		log.info(buggyProject + " Failed Tests: " + this.minErrorTest);
		minErrorTest_ = minErrorTest;
		
		// Read paths of the buggy project.
		this.dp = new DataPreparer(path);
		dp.prepareData(buggyProject);
		
		readPreviouslyFailedTestCases();
		AbstractFixer.deserializeTestCache();
		
//		createDictionary();
	}

	private void readPreviouslyFailedTestCases() {
		String[] failedTestCases = FileHelper.readFile(Configuration.failedTestCasesFilePath + "/" + this.buggyProject + ".txt").split("\n");
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
	// bigold FIXME: add bug being fixed to the cache.
	protected static HashMap<String,FixStatus> patchCache = new HashMap<>();

	// FIXME: add some kind of runtime hook to serialize if the process gets killed prematurely.
	public static void serializeTestCache() {
		try {
			FileOutputStream fos = new FileOutputStream("testcache.ser");
			ObjectOutputStream oos = new ObjectOutputStream(fos);
			oos.writeObject(AbstractFixer.patchCache);
			oos.close();
			fos.close();
			log.info("Serialized test cache to file testcache.ser");
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}

	public static void deserializeTestCache(){
		File fl = new File("testcache.ser");
		HashMap<String, FixStatus> cache = null; 
		if(fl.isFile() && !Configuration.clearTestCache){
			try
			{
				FileInputStream fis = new FileInputStream("testcache.ser");
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
			System.out.println("Deserialized fitnessCache HashMap");
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
	private boolean runtests() {
		try {
			String results = ShellUtils.shellRun(Arrays.asList("java -cp "
					+ PathUtils.buildTestClassPath(dp.classPath, dp.testClassPath)
					+ " org.junit.runner.JUnitCore " + this.failedTestCaseClasses), buggyProject, 2);
			if (results.isEmpty()) {
//					System.err.println(scn.suspiciousJavaFile + "@" + scn.buggyLine);
//					System.err.println("Bug: " + buggyCode);
//					System.err.println("Patch: " + patchCode);
				return false;
			} else {
				if (!results.contains("java.lang.NoClassDefFoundError")) {
					List<String> tempFailedTestCases = readTestResults(results);
					tempFailedTestCases.retainAll(this.fakeFailedTestCasesList);
					if (!tempFailedTestCases.isEmpty()) {
						if (this.failedTestCasesStrList.size() == 1) return false;

						// Might be partially fixed.
						tempFailedTestCases.removeAll(this.failedTestCasesStrList);
						if (!tempFailedTestCases.isEmpty()) return false; // Generate new bugs.
					}
				}
			}
		} catch (IOException e) {
			if (!(this.buggyProject.startsWith("Mockito") || this.buggyProject.startsWith("Closure") || this.buggyProject.startsWith("Time"))) {
				log.debug(buggyProject + " ---Fixer: fix fail because of faile passing previously failed test cases! ");
				return false;
			}
		}
		return true;
	}

	private void postPatchAttemptCleanup(FixStatus status, SuspCodeNode scn, Patch patch, String buggyCode,  String patchCode) {
		patchCache.put(this.buggyProject + patchCode,status);

		if((Configuration.recordAllPatches && status != FixStatus.NOCOMPILE) ||
			(status == FixStatus.PARTIAL) || (status == FixStatus.SUCCESS)) {
			// output 
			String patchStr = TestUtils.readPatch(this.fullBuggyProjectPath);
			System.out.println(patchStr);
			String outputFolder = fixedStatus == FixStatus.SUCCESS ? "/FixedBugs/" : "/PartiallyFixedBugs";
			if (patchStr == null || !patchStr.startsWith("diff")) {
				FileHelper.outputToFile(Configuration.outputPath + this.dataType + outputFolder + buggyProject + "/Patch_" + patchId + "_" + comparablePatches + ".txt",
						"//**********************************************************\n//" + scn.suspiciousJavaFile + " ------ " + scn.buggyLine
						+ "\n//**********************************************************\n"
						+ "===Buggy Code===\n" + buggyCode + "\n\n===Patch Code===\n" + patchCode, false);
			} else {
				FileHelper.outputToFile(Configuration.outputPath + this.dataType + outputFolder + buggyProject + "/Patch_" + patchId + "_" + comparablePatches + ".txt", patchStr, false);
			}
			if (!isTestFixPatterns) {
				this.minErrorTest = 0;
			}
		}
	
		}

	protected FixStatus testGeneratedPatches(List<Patch> patchCandidates, SuspCodeNode scn) {
		// Testing generated patches.
		this.fixedStatus = FixStatus.FAILURE;
		for (Patch patch : patchCandidates) {
			patch.buggyFileName = scn.suspiciousJavaFile;
			String patchedCode = addPatchCodeToFile(scn, patch);// Insert the patch.
			if(patchCache.containsKey(this.buggyProject + patchedCode)) {
				this.fixedStatus = patchCache.get(this.buggyProject + patchedCode);
				if(this.fixedStatus == FixStatus.SUCCESS) return FixStatus.SUCCESS;
				continue;
			} 
			if (this.triedPatchCandidates.contains(patch)) continue;
			patchId++;
			if (patchId > 10000) return FixStatus.FAILURE;
			this.triedPatchCandidates.add(patch);
			
			String buggyCode = patch.getBuggyCodeStr();
			if ("===StringIndexOutOfBoundsException===".equals(buggyCode)) continue;
			String patchCode = patch.getFixedCodeStr1();
			scn.targetClassFile.delete();

			log.debug("Compiling");
            Boolean compiled = this.compile(scn);

            if(compileOnly) {
				postPatchAttemptCleanup(FixStatus.NOCOMPILE, scn, patch, buggyCode, patchedCode);
				continue;
			}

            if(!compiled) {
				log.debug(buggyProject + " ---Fixer: fix fail because of failed compiling! ");
				postPatchAttemptCleanup(FixStatus.NOCOMPILE, scn, patch, buggyCode, patchedCode);
				continue;
			} 
			log.debug("Finished compiling.");
			
			comparablePatches++;
			log.debug("Test previously failed test cases.");
			if(!runtests()) {
				postPatchAttemptCleanup(FixStatus.FAILURE, scn, patch, buggyCode, patchedCode);
                continue;
			}
			List<String> failedTestsAfterFix = new ArrayList<>();
			int errorTestAfterFix = TestUtils.getFailTestNumInProject(fullBuggyProjectPath, failedTestsAfterFix);
			failedTestsAfterFix.removeAll(this.fakeFailedTestCasesList);
			
			if (errorTestAfterFix < minErrorTest) {
			List<String> tmpFailedTestsAfterFix = new ArrayList<>();
			tmpFailedTestsAfterFix.addAll(failedTestsAfterFix);
			tmpFailedTestsAfterFix.removeAll(this.failedTestStrList);
			if (tmpFailedTestsAfterFix.size() > 0) { // Generate new bugs.
				log.debug(buggyProject + " ---Generated new bugs: " + tmpFailedTestsAfterFix.size());
				postPatchAttemptCleanup(FixStatus.FAILURE, scn, patch, buggyCode, patchedCode);
				continue;
			}
			
			// Output the generated patch.
			if (errorTestAfterFix == 0 || failedTestsAfterFix.isEmpty()) {
				fixedStatus = FixStatus.SUCCESS;
				patchCache.put(patchedCode,FixStatus.SUCCESS);
				log.info("Succeeded to fix the bug " + buggyProject + "====================");
			} else if (minErrorTestAfterFix == 0 || errorTestAfterFix <= minErrorTestAfterFix) {
				minErrorTestAfterFix = errorTestAfterFix;
				fixedStatus = FixStatus.PARTIAL;
				patchCache.put(patchedCode,FixStatus.PARTIAL);
				log.info("Partially Succeeded to fix the bug " + buggyProject + "====================");
				minErrorTest_ = minErrorTest_ - (minErrorTest - errorTestAfterFix);
				if (minErrorTest_ <= 0) {
					log.info("Succeeded to fix the bug " + buggyProject + "====================");
					fixedStatus = FixStatus.SUCCESS;
					patchCache.put(patchedCode,FixStatus.SUCCESS);
					minErrorTest = 0;
				}
			}
			postPatchAttemptCleanup(fixedStatus, scn, patch, buggyCode, patchedCode);
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
		return this.fixedStatus;
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
				jsonObject.put("patchCode", patchCode);
				jsonObject.put("patchedJavaFile", patchedJavaFile);
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

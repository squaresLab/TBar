package edu.lu.uni.serval.tbar.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.math.NumberUtils;

public class TestUtils {

	public static List<String> readTestResults(String results) {
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

	public static int getFailTestNumInProject(String projectName, String defects4jPath, List<String> failedTests){
        String testResult = getDefects4jResult(projectName, defects4jPath, "test");
        if (testResult.equals("")){//error occurs in run
            return Integer.MAX_VALUE;
        }
        if (!testResult.contains("Failing tests:")){
            return Integer.MAX_VALUE;
        }
        int errorNum = 0;
        String[] lines = testResult.trim().split("\n");
        for (String lineString: lines){
            if (lineString.startsWith("Failing tests:")){
                errorNum =  Integer.valueOf(lineString.split(":")[1].trim());
                if (errorNum == 0) break;
            } else if (lineString.startsWith("Running ")) {
            	break;
            } else {
            	failedTests.add(lineString.trim());
            }
        }
        return errorNum;
	}
	
//	public static int getFailTestNumInProject(String buggyProject, List<String> failedTests, String classPath,
//			String testClassPath, String[] testCasesArray){
//		StringBuilder builder = new StringBuilder();
//		for (String testCase : testCasesArray) {
//			builder.append(testCase).append(" ");
//		}
//		String testCases = builder.toString();
//		
//		String testResult = "";
//		try {
//			testResult = ShellUtils.shellRun(Arrays.asList("java -cp " + PathUtils.buildClassPath(classPath, testClassPath)
//					+ " org.junit.runner.JUnitCore " + testCases), buggyProject);
//		} catch (IOException e) {
////			e.printStackTrace();
//		}
//		
//        if (testResult.equals("")){//error occurs in run
//            return Integer.MAX_VALUE;
//        }
//        if (!testResult.contains("Failing tests:")){
//            return Integer.MAX_VALUE;
//        }
//        int errorNum = 0;
//        String[] lines = testResult.trim().split("\n");
//        for (String lineString: lines){
//            if (lineString.startsWith("Failing tests:")){
//                errorNum =  Integer.valueOf(lineString.split(":")[1].trim());
//                if (errorNum == 0) break;
//            } else if (lineString.startsWith("Running ")) {
//            	break;
//            } else {
//            	failedTests.add(lineString);
//            }
//        }
//        return errorNum;
//	}
	
	public static int compileProjectWithDefects4j(String projectName, String defects4jPath) {
		String compileResults = getDefects4jResult(projectName, defects4jPath, "compile");
		String[] lines = compileResults.split("\n");
		if (lines.length != 2) return 1;
        for (String lineString: lines){
        	if (!lineString.endsWith("OK")) return 1;
        }
		return 0;
	}

	private static String getDefects4jResult(String projectName, String defects4jPath, String cmdType) {
		try {
			String buggyProject = projectName.substring(projectName.lastIndexOf("/") + 1);
			//which java\njava -version\n
            String result = ShellUtils.shellRun(Arrays.asList("cd " + projectName + "\n", defects4jPath + "framework/bin/defects4j " + cmdType + "\n"), buggyProject, cmdType.equals("test") ? 2 : 1);//"defects4j " + cmdType + "\n"));//
            return result.trim();
        } catch (IOException e){
        	e.printStackTrace();
            return "";
        }
	}

	public static String recoverWithGitCmd(String projectName) {
		try {
			String buggyProject = projectName.substring(projectName.lastIndexOf("/") + 1);
            ShellUtils.shellRun(Arrays.asList("cd " + projectName + "\n", "git checkout -- ."), buggyProject, 1);
            return "";
        } catch (IOException e){
            return "Failed to recover.";
        }
	}

	public static String readPatch(String projectName) {
		try {
			String buggyProject = projectName.substring(projectName.lastIndexOf("/") + 1);
            return ShellUtils.shellRun(Arrays.asList("cd " + projectName + "\n", "git diff"), buggyProject, 1).trim();
        } catch (IOException e){
            return null;
        }
	}

	public static String checkout(String projectName) {
		try {
			String buggyProject = projectName.substring(projectName.lastIndexOf("/") + 1);
            return ShellUtils.shellRun(Arrays.asList("cd " + projectName + "\n", "git checkout -- ."), buggyProject, 1).trim();
        } catch (IOException e){
            return null;
        }
	}

}

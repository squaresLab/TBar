package edu.lu.uni.serval.tbar.fixers;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.lu.uni.serval.tbar.faultloc.AbstractFaultLoc;
import edu.lu.uni.serval.tbar.config.Configuration;
import edu.lu.uni.serval.tbar.context.Dictionary;
import edu.lu.uni.serval.tbar.faultloc.SuspCodeNode;
import edu.lu.uni.serval.tbar.info.Patch;
import edu.lu.uni.serval.tbar.utils.FileHelper;
import edu.lu.uni.serval.tbar.utils.TestUtils;
import edu.lu.uni.serval.tbar.dataprepare.Project;

/**
 * Abstract Fixer.
 * 
 * @author kui.liu
 *
 */
public abstract class AbstractFixer {
	
	private static Logger log = LoggerFactory.getLogger(AbstractFixer.class);
	
	public abstract FixStatus fixProcess();
	public int minErrorTest;                // Number of failed test cases before fixing.
	public int minErrorTest_;
	protected int minErrorTestAfterFix = 0; // Number of failed test cases after fixing
	protected AbstractFaultLoc faultloc = null;

	// All specific failed test cases after testing the buggy project with defects4j command in Java code before fixing.
	// FIXME why are we still using these 
	protected List<String> failedTestStrList = new ArrayList<>();
	// All specific failed test cases after testing the buggy project with defects4j command in terminal before fixing.
	protected List<String> failedTestCasesStrList = new ArrayList<>();
	// The failed test cases after running defects4j command in Java code but not in terminal.
	
	// 0: failed to fix the bug, 1: succeeded to fix the bug. 2: partially succeeded to fix the bug.
	public String dataType = "";
	protected int patchId = 0;
	protected int comparablePatches = 0;
//	private TimeLine timeLine;
	protected Dictionary dic = null;
	
	public void setFaultLoc(AbstractFaultLoc fl) { this.faultloc = fl; }

	protected Project project = null;

	public AbstractFixer(Project project) { 
		this.project = project; 

//		createDictionary();
	}



	protected List<Patch> triedPatchCandidates = new ArrayList<>();
	
	protected FixStatus testGeneratedPatches(List<Patch> patchCandidates, SuspCodeNode scn) {
		FixStatus eventualSuccess = FixStatus.FAILURE;
		// Testing generated patches.
		for (Patch patch : patchCandidates) {
			patch.buggyFileName = scn.suspiciousJavaFile;
			addPatchCodeToFile(scn, patch);// Insert the patch.
			if (this.triedPatchCandidates.contains(patch)) continue;
			patchId++;
			if (patchId > 10000) return eventualSuccess;
			this.triedPatchCandidates.add(patch);
			
			String buggyCode = patch.getBuggyCodeStr();
			if ("===StringIndexOutOfBoundsException===".equals(buggyCode)) continue;
			String patchCode = patch.getFixedCodeStr1();
			scn.targetClassFile.delete();

			log.debug("Compiling");

			if(!project.compile(scn)) continue; 
			log.debug("Finished compiling.");
			comparablePatches++;
			FixStatus fixResult = project.test();
			project.cleanup(scn);

			if(fixResult == FixStatus.SUCCESS || fixResult == FixStatus.PARTIAL) {
				eventualSuccess = eventualSuccess != FixStatus.SUCCESS ? fixResult : eventualSuccess; 

				String folder = (fixResult == FixStatus.SUCCESS) ? "/FixedBugs/" : "/PartiallyFixedBugs/";
				String patchStr = TestUtils.readPatch(project.getFullPathToBuggyProject());

				if(fixResult == FixStatus.SUCCESS) {
					log.info("Succeeded to fix the bug " + project.getProjectName() + "====================");
					System.out.println(patchStr);
				} else {
					log.info("Partially Succeeded to fix the bug " + project.getProjectName() + "====================");
				}
				if (patchStr == null || !patchStr.startsWith("diff")) {
					FileHelper.outputToFile(Configuration.outputPath + this.dataType + folder + project.getProjectName() + "/Patch_" + patchId + "_" + comparablePatches + ".txt",
							"//**********************************************************\n//" + scn.suspiciousJavaFile + " ------ " + scn.buggyLine
							+ "\n//**********************************************************\n"
							+ "===Buggy Code===\n" + buggyCode + "\n\n===Patch Code===\n" + patchCode, false);
				} else {
					FileHelper.outputToFile(Configuration.outputPath + this.dataType + folder + project.getProjectName() + "/Patch_" + patchId + "_" + comparablePatches + ".txt", patchStr, false);
				}
				break;
			}
		}
		return eventualSuccess;
	}

	private void addPatchCodeToFile(SuspCodeNode scn, Patch patch) {
        String javaCode = FileHelper.readFile(scn.javaBackup);
        
		String fixedCodeStr1 = patch.getFixedCodeStr1();
		String fixedCodeStr2 = patch.getFixedCodeStr2();
		int exactBuggyCodeStartPos = patch.getBuggyCodeStartPos();
		int exactBuggyCodeEndPos = patch.getBuggyCodeEndPos();
		String patchCode = fixedCodeStr1;
		boolean needBuggyCode = false;
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
	        String patchedJavaFile = javaCode.substring(0, exactBuggyCodeStartPos) + patchCode + javaCode.substring(exactBuggyCodeEndPos);
	        FileHelper.outputToFile(newFile, patchedJavaFile, false);
	        newFile.renameTo(scn.targetJavaFile);
		} catch (StringIndexOutOfBoundsException e) {
			log.debug(exactBuggyCodeStartPos + " ==> " + exactBuggyCodeEndPos + " : " + javaCode.length());
			e.printStackTrace();
			buggyCode = "===StringIndexOutOfBoundsException===";
		}
        
        patch.setBuggyCodeStr(buggyCode);
        patch.setFixedCodeStr1(patchCode);
	}
	
}

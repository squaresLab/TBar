package edu.lu.uni.serval.tbar.fixers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.lu.uni.serval.jdt.tree.ITree;
import edu.lu.uni.serval.tbar.config.Configuration;
import edu.lu.uni.serval.tbar.context.ContextReader;
import edu.lu.uni.serval.tbar.faultloc.SuspCodeNode;
import edu.lu.uni.serval.tbar.fixpatterns.CNIdiomNoSuperCall;
import edu.lu.uni.serval.tbar.fixpatterns.ClassCastChecker;
import edu.lu.uni.serval.tbar.fixpatterns.ConditionalExpressionMutator;
import edu.lu.uni.serval.tbar.fixpatterns.DataTypeReplacer;
import edu.lu.uni.serval.tbar.fixpatterns.ICASTIdivCastToDouble;
import edu.lu.uni.serval.tbar.fixpatterns.LiteralExpressionMutator;
import edu.lu.uni.serval.tbar.fixpatterns.MethodInvocationMutator;
import edu.lu.uni.serval.tbar.fixpatterns.NPEqualsShouldHandleNullArgument;
import edu.lu.uni.serval.tbar.fixpatterns.NullPointerChecker;
import edu.lu.uni.serval.tbar.fixpatterns.OperatorMutator;
import edu.lu.uni.serval.tbar.fixpatterns.RangeChecker;
import edu.lu.uni.serval.tbar.fixpatterns.ReturnStatementMutator;
import edu.lu.uni.serval.tbar.fixpatterns.StatementInserter;
import edu.lu.uni.serval.tbar.fixpatterns.StatementMover;
import edu.lu.uni.serval.tbar.fixpatterns.StatementRemover;
import edu.lu.uni.serval.tbar.fixpatterns.VariableReplacer;
import edu.lu.uni.serval.tbar.fixtemplate.FixTemplate;
import edu.lu.uni.serval.tbar.info.Patch;
import edu.lu.uni.serval.tbar.utils.Checker;
import edu.lu.uni.serval.tbar.utils.FileHelper;
import edu.lu.uni.serval.tbar.utils.SuspiciousPosition;

/**
 * 
 * @author kui.liu
 *
 */
@SuppressWarnings("unused")
public class TBarFixer extends AbstractFixer {

	private static Logger log = LoggerFactory.getLogger(TBarFixer.class);
	
	public TBarFixer(String path, String projectName, int bugId) {
		super(path, projectName, bugId);
	}

	private JSONArray jsonReader() throws ParseException, FileNotFoundException, IOException {
		JSONParser parser = new JSONParser();
		Reader reader = new FileReader(Configuration.patchRankFile);
		Object jsonObj = parser.parse(reader);
		JSONObject jsonObject = (JSONObject) jsonObj;
		return (JSONArray) jsonObject.get(Configuration.bugId);
	}
	
	@Override
	public FixStatus fixProcess() {
		// Read paths of the buggy project.
		if (!dp.validPaths) return FixStatus.FAILURE;
		
		// Read suspicious positions.
		List<SuspiciousPosition> suspiciousCodeList = faultloc.getSuspiciousCodeList();

		if (suspiciousCodeList == null) return FixStatus.FAILURE;
		
		FixStatus status = FixStatus.FAILURE;
		List<SuspCodeNode> triedSuspNode = new ArrayList<>();
		log.info("=======TBar: Start to fix suspicious code======");


		List<Patch> rankedPatchCandidates = new ArrayList<>();
		if (!Configuration.patchRankFile.isEmpty()) {
			try{
				JSONArray patchCodes =	jsonReader();
				@SuppressWarnings("unchecked")
				Iterator<JSONObject> it = patchCodes.iterator();
				while (it.hasNext()) {
					JSONObject temp_patch = it.next();
					String fixedCodeStr1 = (String) temp_patch.get("patch_code1");
					String fixedCodeStr2 = (String) temp_patch.get("patch_code2");
					Patch patch = new Patch();
					patch.setFixedCodeStr1(fixedCodeStr1);
					if (fixedCodeStr2 != null) {
						patch.setFixedCodeStr2(fixedCodeStr2);
					}
					Long exactBuggyCodeStartPos = (Long) temp_patch.get("exactBuggyCodeStartPos");
					Long exactBuggyCodeEndPos = (Long) temp_patch.get("exactBuggyCodeEndPos");
					Integer exactBuggyCodeStartPosInt = exactBuggyCodeStartPos.intValue();
					Integer exactBuggyCodeEndPosInt = exactBuggyCodeEndPos.intValue();
					patch.setBuggyCodeStartPos(exactBuggyCodeStartPosInt);
					patch.setBuggyCodeEndPos(exactBuggyCodeEndPosInt);
					rankedPatchCandidates.add(patch);
				}	
			} catch (ParseException e ) {
					e.printStackTrace();
			} catch (FileNotFoundException e) {
					e.printStackTrace();
			} catch (IOException e) {
					e.printStackTrace();
			}
		}


		for (SuspiciousPosition suspiciousCode : suspiciousCodeList) {
			List<SuspCodeNode> scns = faultloc.getSuspiciousCode(suspiciousCode);
			if (scns == null) continue;

			for (SuspCodeNode scn : scns) {
//				log.debug(scn.suspCodeStr);
				if (triedSuspNode.contains(scn)) continue;
				triedSuspNode.add(scn);
				
				// Parse context information of the suspicious code.
				List<Integer> contextInfoList = readAllNodeTypes(scn.suspCodeAstNode);
				List<Integer> distinctContextInfo = new ArrayList<>();
				for (Integer contInfo : contextInfoList) {
					if (!distinctContextInfo.contains(contInfo) && !Checker.isBlock(contInfo)) {
						distinctContextInfo.add(contInfo);
					}
				}
//				List<Integer> distinctContextInfo = contextInfoList.stream().distinct().collect(Collectors.toList());
				
		        // Match fix templates for this suspicious code with its context information.
				status = fixWithMatchedFixTemplates(scn, distinctContextInfo, rankedPatchCandidates);
		        
				if (!isTestFixPatterns && status == FixStatus.SUCCESS) { // FIXME: no accounting for PARTIAL success here or elsewhere
					break;
				}
				if (this.patchId >= 10000) break;
			}
			if (!isTestFixPatterns && status == FixStatus.SUCCESS) {
				break;
			}
			if (this.patchId >= 10000) break;
        }
		log.info("=======TBar: Finish off fixing======");
		
		FileHelper.deleteDirectory(Configuration.TEMP_FILES_PATH + this.dataType + "/" + this.buggyProject);
		return status;

	}

	

	public FixStatus fixWithMatchedFixTemplates(SuspCodeNode scn, List<Integer> distinctContextInfo, List<Patch> rankedPatchCandidates) {
		// generate patches with fix templates of TBar.
		List<FixTemplate> fts = new ArrayList<FixTemplate>();
		

		if (!Checker.isMethodDeclaration(scn.suspCodeAstNode.getType())) {
			boolean nullChecked = false;
			boolean typeChanged = false;
			boolean methodChanged = false;
			boolean operator = false;
				
			for (Integer contextInfo : distinctContextInfo) {
				if (Checker.isCastExpression(contextInfo)) {
					fts.add(new ClassCastChecker()); 
					if (!typeChanged) {
						typeChanged = true;
						fts.add(new DataTypeReplacer());
					}
				} else if (Checker.isClassInstanceCreation(contextInfo)) {
					if (!methodChanged) {
						methodChanged = true;
						fts.add(new MethodInvocationMutator());
					}
				} else if (Checker.isIfStatement(contextInfo) || Checker.isDoStatement(contextInfo) || Checker.isWhileStatement(contextInfo)) {
					if (Checker.isInfixExpression(scn.suspCodeAstNode.getChild(0).getType()) && !operator) {
						operator = true;
						fts.add(new OperatorMutator(0));
					}
					fts.add(new ConditionalExpressionMutator(2));
				} else if (Checker.isConditionalExpression(contextInfo)) {
					fts.add(new ConditionalExpressionMutator(0));
				} else if (Checker.isCatchClause(contextInfo) || Checker.isVariableDeclarationStatement(contextInfo)) {
					if (!typeChanged) {
						fts.add(new DataTypeReplacer());
						typeChanged = true;
					}
				} else if (Checker.isInfixExpression(contextInfo)) {
					fts.add(new ICASTIdivCastToDouble());
					if (!operator) {
						operator = true;
						fts.add(new OperatorMutator(0));
					}
					
					fts.add(new ConditionalExpressionMutator(1));
					fts.add(new OperatorMutator(4));
				} else if (Checker.isBooleanLiteral(contextInfo) || Checker.isNumberLiteral(contextInfo) || Checker.isCharacterLiteral(contextInfo)|| Checker.isStringLiteral(contextInfo)) {
					fts.add(new LiteralExpressionMutator());
				} else if (Checker.isMethodInvocation(contextInfo) || Checker.isConstructorInvocation(contextInfo) || Checker.isSuperConstructorInvocation(contextInfo)) {
					if (!methodChanged) {
						fts.add(new MethodInvocationMutator());
						methodChanged = true;
					}					
					if (Checker.isMethodInvocation(contextInfo)) {
						fts.add(new NPEqualsShouldHandleNullArgument());
						fts.add(new RangeChecker(false));
					}
				} else if (Checker.isAssignment(contextInfo)) {
					fts.add(new OperatorMutator(2));
				} else if (Checker.isInstanceofExpression(contextInfo)) {
					fts.add(new OperatorMutator(5));
				} else if (Checker.isArrayAccess(contextInfo)) {
					fts.add(new RangeChecker(true));
				} else if (Checker.isReturnStatement(contextInfo)) {
					String returnType = ContextReader.readMethodReturnType(scn.suspCodeAstNode);
					if ("boolean".equalsIgnoreCase(returnType)) {
						fts.add(new ConditionalExpressionMutator(2));
					} else {
						fts.add(new ReturnStatementMutator(returnType));
					}
				} else if (Checker.isSimpleName(contextInfo) || Checker.isQualifiedName(contextInfo)) {
					fts.add(new VariableReplacer());
					if (!nullChecked) {
						nullChecked = true;
						fts.add(new NullPointerChecker());
					}
				} 
			}
			
			if (!nullChecked) {
				nullChecked = true;
				fts.add(new NullPointerChecker());
			}

			fts.add(new StatementMover());
			fts.add(new StatementRemover());
			fts.add(new StatementInserter());
		} else {
			fts.add(new StatementRemover());
		}
		for(FixTemplate ft : fts) {
			FixStatus status = generateAndValidatePatches(ft, scn, rankedPatchCandidates);
			if(status == FixStatus.SUCCESS || status == FixStatus.PARTIAL) return FixStatus.SUCCESS;
		}
		return FixStatus.FAILURE;
	}
	
	private String readDirectory() {
		int index = dataType.indexOf("/");
		if (index > -1) dataType = dataType.substring(0, index);
		return dataType;
	}
	
	protected FixStatus generateAndValidatePatches(FixTemplate ft, SuspCodeNode scn, List<Patch> rankedPatchCandidates) {
		ft.setSuspiciousCodeStr(scn.suspCodeStr);
		ft.setSuspiciousCodeTree(scn.suspCodeAstNode);
		if (scn.javaBackup == null) ft.setSourceCodePath(dp.srcPath);
		else ft.setSourceCodePath(dp.srcPath, scn.javaBackup);
		ft.setDictionary(dic);
		ft.generatePatches();
		List<Patch> patchCandidates = ft.getPatches();
//		System.out.println(dataType + " ====== " + patchCandidates.size());
		
		// Test generated patches.
		if (patchCandidates.isEmpty()) return FixStatus.FAILURE;
		return testGeneratedPatches(patchCandidates, rankedPatchCandidates, scn);
	}
	
	public List<Integer> readAllNodeTypes(ITree suspCodeAstNode) {
		List<Integer> nodeTypes = new ArrayList<>();
		nodeTypes.add(suspCodeAstNode.getType());
		List<ITree> children = suspCodeAstNode.getChildren();
		for (ITree child : children) {
			int childType = child.getType();
			if (Checker.isFieldDeclaration(childType) || 
					Checker.isMethodDeclaration(childType) ||
					Checker.isTypeDeclaration(childType) ||
					Checker.isStatement(childType)) break;
			nodeTypes.addAll(readAllNodeTypes(child));
		}
		return nodeTypes;
	}

}

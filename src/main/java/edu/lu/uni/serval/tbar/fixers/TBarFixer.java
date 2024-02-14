package edu.lu.uni.serval.tbar.fixers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

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
	
	@Override
	public void fixProcess() {
		// Read paths of the buggy project.
		if (!dp.validPaths) return;
		
		// Read suspicious positions.
		List<SuspiciousPosition> suspiciousCodeList = faultloc.getSuspiciousCodeList();

		if (suspiciousCodeList == null) return;
		
		List<SuspCodeNode> triedSuspNode = new ArrayList<>();
		log.info("=======TBar: Start to fix suspicious code======");
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
				fixWithMatchedFixTemplates(scn, distinctContextInfo);
		        
				if (!isTestFixPatterns && minErrorTest == 0) break;
				if (this.patchId >= 10000) break;
			}
			if (!isTestFixPatterns && minErrorTest == 0) break;
			if (this.patchId >= 10000) break;
        }
		log.info("=======TBar: Finish off fixing======");
		
		FileHelper.deleteDirectory(Configuration.TEMP_FILES_PATH + this.dataType + "/" + this.buggyProject);
	}

	public void fixWithMatchedFixTemplates(SuspCodeNode scn, List<Integer> distinctContextInfo) {
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
			FixStatus status = generateAndValidatePatches(ft, scn);
			if(status == FixStatus.SUCCESS) return;
		}
	}
	
	private String readDirectory() {
		int index = dataType.indexOf("/");
		if (index > -1) dataType = dataType.substring(0, index);
		return dataType;
	}
	
	protected FixStatus generateAndValidatePatches(FixTemplate ft, SuspCodeNode scn) {
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
		return testGeneratedPatches(patchCandidates, scn);
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

package edu.lu.uni.serval.tbar.dataprepare;



import java.io.IOException;
import java.util.Arrays;

import edu.lu.uni.serval.tbar.config.Configuration;
import edu.lu.uni.serval.tbar.faultloc.SuspCodeNode;
import edu.lu.uni.serval.tbar.utils.PathUtils;
import edu.lu.uni.serval.tbar.utils.ShellUtils;
import edu.lu.uni.serval.tbar.utils.TestUtils;
import edu.lu.uni.serval.tbar.fixers.FixStatus;


public class MCTProject extends Project {


    public MCTProject(String name, int num, String path) {
        super(name, num, path);
    }

    @Override
    public boolean compile(SuspCodeNode scn) {
        super.compile(scn);
        if (!scn.targetClassFile.exists()) { // fail to compile
            return TestUtils.compileProjectWithDefects4j(this.fullPathToBuggyProject,
                                    Configuration.defects4jPath) == 1 ? true : false;
        }
        return true;
    }

    @Override
    protected String subtest() {
        String results = "";
        try {
           results  =  ShellUtils.shellRun(Arrays.asList("java -cp "
                    + PathUtils.buildTestClassPath(dp.classPath, dp.testClassPath)
                    + " org.junit.runner.JUnitCore " + this.failedTestCaseClasses), this.projectName, 2);
        } catch (IOException e) {
            // swallow the exception on purpose, not sure why
        }
        return results;
    }
    
}

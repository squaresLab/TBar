package edu.lu.uni.serval.tbar.dataprepare;

import static edu.lu.uni.serval.tbar.dataprepare.Project.log;

import java.io.IOException;
import java.util.Arrays;

import edu.lu.uni.serval.tbar.faultloc.SuspCodeNode;
import edu.lu.uni.serval.tbar.utils.PathUtils;
import edu.lu.uni.serval.tbar.utils.ShellUtils;

public class GenProject extends Project {

    public GenProject(String name, int num, String path) {
        super(name, num, path);
    }

    @Override
    public boolean compile(SuspCodeNode scn) {
        super.compile(scn);
        return scn.targetClassFile.exists();
    } 

    @Override
    protected String subtest() {
        String results = "";
        try {
           results  =  ShellUtils.shellRun(Arrays.asList("java -cp "
                    + PathUtils.buildTestClassPath(dp.classPath, dp.testClassPath)
                    + " org.junit.runner.JUnitCore " + this.failedTestCaseClasses), this.projectName, 2);
        } catch (IOException e) {
            log.debug(this.projectName + " ---Fixer: fix fail because of faile passing previously failed test cases! ");
           return "";
        }
        return results;
    }
}

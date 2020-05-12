package org.jmlspecs.openjmltest;

import org.junit.platform.runner.JUnitPlatform;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;
import org.jmlspecs.openjmltest.testcases.*;

//@RunWith(JUnitPlatform.class)
//@SelectPackages({"org.jmlspecs.openjmltest.testcases"})
//@RunWith(com.googlecode.junittoolbox.WildcardPatternSuite.class)
//@com.googlecode.junittoolbox.SuiteClasses({"testcases/*.class"})
@RunWith(Suite.class)
@SuiteClasses({ access.class, api.class, arith.class, assignable.class, binaries.class, bugs.class, compilationUnit.class, compiler.class, deprecation.class, esc.class, escaccessible.class,
                        escall2.class, escall3.class, escArithmeticModes.class, escArithmeticModes2.class, escbitvector.class, esccallable.class, esccode.class, escConstantFields.class,
                        escconstructor.class, escCounterexamples.class, escDemofiles.class, escenums.class, escfeatures.class, escfiles.class, escfilesTrace.class, escfunction.class, escgeneric.class,
                        escinclause.class, escinline.class, escJML.class, esclambdas.class, esclocation.class, escm.class, escnew.class, escnew2.class, escnew3.class, escnewassignable.class,
                        escnewBoxing.class, escnonpublic.class, escnowarn.class, escoption.class, escprimitivetypes.class, escreadable.class, escstrings.class, escTiming.class, escTrace.class,
                        escvisibility.class, escvisibility1.class, expressions.class, flow.class, generics.class, harnesstests.class, internalSpecs.class, jmldoc.class, jmltypes.class,
                        lblexpression.class, matchClasses.class, methodspecs.class, misctests.class, modelghost.class, modifiers.class, namelookup.class, notspecified.class, nowarn.class,
                        positions.class, prettyprinting.class, purity.class, QueryPure.class, QuerySecret.class, racArithmeticModes.class, racdemoexamples.class, racfeatures.class, racfiles.class,
                        racJML.class, racnew.class, racnew2.class, racnew3.class, racnewLoops.class, racnewWithSpecs.class, racnonpublic.class, racreadable.class, racsystem.class, redundant.class,
                        scanner.class, SFBugs.class, SpecsBase.class, SpecsEsc.class, SpecsRac.class, statements.class, strict.class, strongarm.class, sysclasses.class, typechecking.class,
                        typecheckingJmlTypes.class, typecheckingvisibility.class, typeclauses.class })
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
public class AllTests {

}
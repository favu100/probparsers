package de.be4.classicalb.core.parser.languageextension;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.OutputStream;

import org.junit.Test;

import util.Helpers;
import de.be4.classicalb.core.parser.BParser;
import de.be4.classicalb.core.parser.ParsingBehaviour;
import de.be4.classicalb.core.parser.analysis.Ast2String;
import de.be4.classicalb.core.parser.exceptions.BException;
import de.be4.classicalb.core.parser.exceptions.CheckException;
import de.be4.classicalb.core.parser.extensions.RuleGrammar;
import de.be4.classicalb.core.parser.node.Node;
import de.be4.classicalb.core.parser.node.Start;
import de.hhu.stups.sablecc.patch.SourcePosition;
import de.prob.prolog.output.IPrologTermOutput;
import de.prob.prolog.output.PrologTermOutput;

public class RuleExtensionsTest {

	@Test
	public void testForAllSubstitution() throws Exception {
		final String testMachine = "#SUBSTITUTION FORALL x WHERE x : 1..10 EXPECT 1=1 THEN skip ELSE skip END";
		final String result = getTreeAsString(testMachine);
		assertEquals(
				"Start(ASubstitutionParseUnit(AIfSubstitution(AForallPredicate([AIdentifierExpression([x])],AImplicationPredicate(AMemberPredicate(AIdentifierExpression([x]),AIntervalExpression(AIntegerExpression(1),AIntegerExpression(10))),AEqualPredicate(AIntegerExpression(1),AIntegerExpression(1)))),ASkipSubstitution(),[],AAnySubstitution([AIdentifierExpression([x])],AConjunctPredicate(AMemberPredicate(AIdentifierExpression([x]),AIntervalExpression(AIntegerExpression(1),AIntegerExpression(10))),ANegationPredicate(AEqualPredicate(AIntegerExpression(1),AIntegerExpression(1)))),ASkipSubstitution()))))",
				result);
	}

	@Test
	public void testForAllPredicate() {
		String file = "src/test/resources/languageextensions/rules/ForAllPredicate.mch";
		String result = Helpers.fullParsing(file);
		System.out.println(result);
		assertFalse(result.contains("exception"));
	}

	@Test
	public void testDefinitionInjection() throws Exception {
		final String testMachine = "RULES_MACHINE Test OPERATIONS RULE rule1 = RULE_FORALL r WHERE r : 1..3 EXPECT 1=2 COUNTEREXAMPLE \"foo\"  END END";
		final String result = getTreeAsString(testMachine);
		assertTrue(result.contains("AExpressionDefinitionDefinition(TO_STRING"));
	}

	@Test
	public void testRuleOperation() throws Exception {
		final String testMachine = "RULES_MACHINE Test OPERATIONS RULE foo = BEGIN RULE_SUCCESS END END";
		final String result = Helpers.getMachineAsPrologTerm(testMachine);
		System.out.println(result);
		assertTrue("Checking variables",
				result.contains("[variables(4,[identifier(5,foo),identifier(6,foo_Counterexamples)])"));
		assertTrue("Checking invariant", result.contains(
				"invariant(7,conjunct(8,member(9,identifier(10,foo),set_extension(11,[string(12,'FAIL'),string(13,'SUCCESS'),string(14,'NOT_CHECKED'),string(15,'DISABLED')])),member(16,identifier(17,foo_Counterexamples),pow_subset(18,string_set(19)))))"));
	}

	@Test
	public void testConstantDependencies() throws Exception {
		final String testMachine = "RULES_MACHINE Test CONSTANTS k PROPERTIES k = FALSE OPERATIONS RULE foo = SELECT \nCONSTANT_DEPENDENCIES\n(k = TRUE) THEN RULE_SUCCESS END END";
		final String result = Helpers.getMachineAsPrologTerm(testMachine);
		System.out.println(result);
		assertTrue("Checking Invariant", result.contains(
				"set_extension(11,[string(12,'FAIL'),string(13,'SUCCESS'),string(14,'NOT_CHECKED'),string(15,'DISABLED')])"));
		assertTrue("Checking Initialisation", result.contains(
				"if_then_else(24,equal(25,identifier(26,k),boolean_true(27)),string(28,'NOT_CHECKED'),string(29,'DISABLED'))"));
	}

	@Test
	public void testRuleOperationMissingFailOrSuccess() {
		try {
			final String testMachine = "RULES_MACHINE Test OPERATIONS\n RULE foo = BEGIN skip END END";
			getTreeAsString(testMachine);
			fail("Missing exception.");
		} catch (BException e) {
			assertEquals("No result value assigned in rule operation", e.getMessage());
			CheckException check = (CheckException) e.getCause();
			Node node = check.getFirstNode();
			assertEquals(2, node.getStartPos().getLine());
			assertEquals(2, node.getStartPos().getPos());
		}
	}

	@Test
	public void testRuleFailAsAnOrdinaryIdentifierInANormalMachine() throws Exception {
		final String testMachine = "MACHINE Test CONSTANTS RULE_FAI PROPERTIES RULE_FAI = 1 END";
		String result = getTreeAsString(testMachine);
		assertEquals(
				"Start(AAbstractMachineParseUnit(AMachineHeader([Test],[]),[AConstantsMachineClause([AIdentifierExpression([RULE_FAI])]),APropertiesMachineClause(AEqualPredicate(AIdentifierExpression([RULE_FAI]),AIntegerExpression(1)))]))",
				result);
	}

	@Test
	public void testDuplicateRuleAssignStatement() throws Exception {
		try {
			final String testMachine = "RULES_MACHINE Test OPERATIONS RULE foo = BEGIN RULE_SUCCESS;\nRULE_SUCCESS  END END";
			getTreeAsString(testMachine);
			fail("Exception expected");
		} catch (BException e) {
			assertEquals("Result value of rule operation is assigned more than once", e.getMessage());
			CheckException check = (CheckException) e.getCause();
			SourcePosition startPos = check.getFirstNode().getStartPos();
			assertEquals(2, startPos.getLine());
			assertEquals(1, startPos.getPos());
		}
	}

	@Test
	public void testOrdinaryOperationInRulesMachine() throws Exception {
		final String testMachine = "RULES_MACHINE Test OPERATIONS foo = BEGIN skip END END";
		getTreeAsString(testMachine);
	}

	@Test
	public void testOrdinaryOperationWithIfThenElse() throws Exception {
		final String testMachine = "RULES_MACHINE Test OPERATIONS foo = IF 1=1 THEN skip ELSE skip END END";
		getTreeAsString(testMachine);
	}

	@Test
	public void testRuleSuccessInOrdinaryOperation() throws Exception {
		try {
			final String testMachine = "RULES_MACHINE Test OPERATIONS foo = IF 1=1 THEN RULE_SUCCESS ELSE skip END END";
			getTreeAsString(testMachine);
			fail("Exception expected");
		} catch (BException e) {
			assertEquals("RULE_SUCCESS used outside of a RULE operation", e.getMessage());
			CheckException check = (CheckException) e.getCause();
			SourcePosition startPos = check.getFirstNode().getStartPos();
			assertEquals(1, startPos.getLine());
			assertEquals(49, startPos.getPos());
		}
	}

	@Test
	public void testRuleFailInOrdinaryOperation() throws Exception {
		try {
			final String testMachine = "RULES_MACHINE Test OPERATIONS foo = IF 1=1 THEN RULE_FAIL ELSE skip END END";
			getTreeAsString(testMachine);
			fail("Exception expected");
		} catch (BException e) {
			assertEquals("RULE_FAIL used outside of a RULE operation", e.getMessage());
			CheckException check = (CheckException) e.getCause();
			SourcePosition startPos = check.getFirstNode().getStartPos();
			assertEquals(1, startPos.getLine());
			assertEquals(49, startPos.getPos());
		}
	}

	@Test
	public void testRuleFailNoMessage() throws Exception {
		final String testMachine = "RULES_MACHINE Test OPERATIONS RULE foo = BEGIN RULE_FAIL END END";
		final String result = Helpers.getMachineAsPrologTerm(testMachine);
		System.out.println(result);
		assertTrue("Checking substitution", result.contains(
				"assign(40,[identifier(41,foo),identifier(42,'#RESULT'),identifier(43,'#COUNTEREXAMPLE'),identifier(44,foo_Counterexamples)],[string(45,'FAIL'),string(46,'FAIL'),empty_set(47),empty_set(48)]))"));
	}

	@Test
	public void testRuleFail() throws Exception {
		final String testMachine = "MACHINE Test OPERATIONS RULE foo = BEGIN RULE_FAIL(\"foo is violated\") END END";
		getTreeAsString(testMachine);
	}

	@Test
	public void testRuleOperationAndExistingVariables() throws Exception {
		final String testMachine = "RULES_MACHINE Test VARIABLES x INVARIANT x = 1 INITIALISATION x:= 1 OPERATIONS RULE foo = BEGIN RULE_SUCCESS END END";
		getTreeAsString(testMachine);
	}

	@Test
	public void testRuleIfThenElse() throws Exception {
		final String testMachine = "RULES_MACHINE Test OPERATIONS RULE foo = BEGIN IF 1=1 THEN RULE_SUCCESS ELSE RULE_FAIL END END END";
		getTreeAsString(testMachine);
	}

	@Test
	public void testSequencingSubstitution() throws Exception {
		final String testMachine = "RULES_MACHINE Test OPERATIONS RULE foo = BEGIN RULE_SUCCESS;skip END END";
		getTreeAsString(testMachine);
		final String testMachine2 = "RULES_MACHINE Test OPERATIONS RULE foo = BEGIN skip;RULE_SUCCESS END END";
		getTreeAsString(testMachine2);
	}

	@Test
	public void testForLoop() throws Exception {
		final String testMachine = "MACHINE Test OPERATIONS foo = \nFOR x IN 1..3 \nDO skip END END";
		String treeAsString = getTreeAsString(testMachine);
		System.out.println(treeAsString);
	}

	@Test
	public void testIfWithoutElseBranch() throws Exception {
		try {
			final String testMachine = "RULES_MACHINE Test OPERATIONS foo = IF 1=1 THEN RULE_FAIL ELSE skip END END";
			getTreeAsString(testMachine);
			fail("Exception expected");
		} catch (BException e) {
			assertEquals("RULE_FAIL used outside of a RULE operation", e.getMessage());
			CheckException check = (CheckException) e.getCause();
			SourcePosition startPos = check.getFirstNode().getStartPos();
			assertEquals(1, startPos.getLine());
			assertEquals(49, startPos.getPos());
		}
	}

	@Test
	public void testSequencingSubstitution2() throws Exception {
		final String testMachine = "RULES_MACHINE Test OPERATIONS RULE foo = BEGIN RULE_SUCCESS;IF 1=1 THEN skip END END END";
		getTreeAsString(testMachine);
	}

	@Test
	public void testSequencingSubstitution3() throws Exception {
		try {
			final String testMachine = "RULES_MACHINE Test OPERATIONS \nRULE foo = BEGIN skip;IF 1=1 THEN skip END END END";
			getTreeAsString(testMachine);
			fail("Exception expected");
		} catch (BException e) {
			assertEquals("No result value assigned in rule operation", e.getMessage());
			CheckException check = (CheckException) e.getCause();
			SourcePosition startPos = check.getFirstNode().getStartPos();
			assertEquals(2, startPos.getLine());
			assertEquals(1, startPos.getPos());
		}
	}

	@Test
	public void testResultOnlySetInTheElseBranch() throws Exception {
		try {
			final String testMachine = "RULES_MACHINE Test OPERATIONS RULE foo = BEGIN \nIF 1=1 THEN skip ELSE RULE_SUCCESS END END END";
			getTreeAsString(testMachine);
			fail("Exception expected");
		} catch (BException e) {
			assertEquals("Result value is not set in the THEN branch but set in the ELSE branch", e.getMessage());
			CheckException check = (CheckException) e.getCause();
			SourcePosition startPos = check.getFirstNode().getStartPos();
			assertEquals(2, startPos.getLine());
			assertEquals(1, startPos.getPos());
		}
	}

	@Test
	public void testRuleSkip() throws Exception {
		try {
			final String testMachine = "RULES_MACHINE Test OPERATIONS \nRULE foo = skip END";
			getTreeAsString(testMachine);
			fail("Expected exception");
		} catch (BException e) {
			assertEquals("No result value assigned in rule operation", e.getMessage());
			final CheckException check = (CheckException) e.getCause();
			final SourcePosition startPos = check.getFirstNode().getStartPos();
			assertEquals(2, startPos.getLine());
			assertEquals(1, startPos.getPos());
		}
	}

	@Test
	public void testAllowedDefinitionCall() throws Exception {
		final String testMachine = "RULES_MACHINE Test DEFINITIONS foo == skip OPERATIONS RULE rule1 = BEGIN RULE_SUCCESS; foo END END";
		getTreeAsString(testMachine);
	}

	@Test
	public void testInvalidDefinitionCall() throws Exception {
		try {
			final String testMachine = "RULES_MACHINE Test DEFINITIONS foo == \nRULE_FAIL OPERATIONS RULE rule1 = BEGIN RULE_SUCCESS; foo END END";
			getTreeAsString(testMachine);
			fail("Expected exception");
		} catch (BException e) {
			assertEquals("RULE_FAIL used outside of a RULE operation", e.getMessage());
			CheckException check = (CheckException) e.getCause();
			SourcePosition startPos = check.getFirstNode().getStartPos();
			assertEquals(2, startPos.getLine());
			assertEquals(1, startPos.getPos());
		}
	}

	@Test
	public void testRuleIfThenElseError() throws Exception {
		try {
			final String testMachine = "RULES_MACHINE Test OPERATIONS RULE foo = \nIF 1=1 THEN RULE_SUCCESS ELSE skip END END";
			getTreeAsString(testMachine);
			fail("Expected exception");
		} catch (BException e) {
			assertEquals("Result value is set in the THEN branch but not in ELSE branch", e.getMessage());
			CheckException check = (CheckException) e.getCause();
			SourcePosition startPos = check.getFirstNode().getStartPos();
			assertEquals(2, startPos.getLine());
			assertEquals(1, startPos.getPos());
		}
	}

	@Test
	public void testElseIfError() throws Exception {
		try {
			final String testMachine = "RULES_MACHINE Test OPERATIONS RULE foo = IF 1=1 THEN RULE_SUCCESS \nELSIF 1=1 THEN skip ELSE RULE_SUCCESS END END";
			getTreeAsString(testMachine);
			fail("Expected exception");
		} catch (BException e) {
			assertEquals("Result value is set in the THEN branch but not in ELSIF branch", e.getMessage());
			CheckException check = (CheckException) e.getCause();
			SourcePosition startPos = check.getFirstNode().getStartPos();
			assertEquals(2, startPos.getLine());
			assertEquals(1, startPos.getPos());
		}
	}

	@Test
	public void testIfElseNoError() throws Exception {
		final String testMachine = "RULES_MACHINE Test OPERATIONS RULE foo = SELECT 1=1 THEN IF 1=1 THEN skip ELSE skip END ; RULE_SUCCESS END END";
		getTreeAsString(testMachine);
	}

	@Test
	public void testElseIf2Error() throws Exception {
		try {
			final String testMachine = "RULES_MACHINE Test OPERATIONS RULE foo = IF 1=1 THEN skip \nELSIF 1=1 THEN RULE_SUCCESS ELSE skip END END";
			getTreeAsString(testMachine);
			fail("Expected exception");
		} catch (BException e) {
			assertEquals("Result value is not set in the THEN branch but set in ELSIF branch", e.getMessage());
			CheckException check = (CheckException) e.getCause();
			SourcePosition startPos = check.getFirstNode().getStartPos();
			assertEquals(2, startPos.getLine());
			assertEquals(1, startPos.getPos());
		}
	}

	@Test
	public void testDependsOnRule() throws Exception {
		final String testMachine = "MACHINE Test OPERATIONS RULE foo = BEGIN RULE_SUCCESS END;"
				+ " RULE foo2 = SELECT DEPENDS_ON_RULES(foo) THEN RULE_SUCCESS END END";
		getTreeAsString(testMachine);

	}

	@Test
	public void testGoal() throws Exception {
		final String testMachine = "RULES_MACHINE Test DEFINITIONS GOAL == SUCCEEDED_RULES(rule1) & 1=1 OPERATIONS RULE rule1 = BEGIN RULE_SUCCESS END END";
		final String result = Helpers.getMachineAsPrologTerm(testMachine);
		System.out.println(result);
	}

	@Test
	public void testSucceededRules() throws Exception {
		final String testMachine = "MACHINE Test OPERATIONS RULE foo = BEGIN RULE_SUCCESS END;"
				+ " RULE foo2 = SELECT SUCCEEDED_RULES(foo) THEN RULE_SUCCESS END END";
		getTreeAsString(testMachine);

	}

	@Test
	public void testFailedRules() throws Exception {
		final String testMachine = "MACHINE Test OPERATIONS RULE foo = BEGIN RULE_FAIL END;"
				+ " RULE foo2 = SELECT FAILED_RULES(foo) THEN RULE_SUCCESS END END";
		getTreeAsString(testMachine);

	}

	public static String getTreeAsString(final String testMachine) throws BException {
		// System.out.println("Parsing \"" + testMachine + "\"");
		final BParser parser = new BParser("testcase");
		parser.getOptions().grammar = RuleGrammar.getInstance();
		final Start startNode = parser.parse(testMachine, false);

		// startNode.apply(new ASTPrinter());
		final Ast2String ast2String = new Ast2String();
		startNode.apply(ast2String);
		final String string = ast2String.toString();
		// System.out.println(string);
		return string;
	}

}

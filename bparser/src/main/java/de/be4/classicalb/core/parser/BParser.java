package de.be4.classicalb.core.parser;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import de.be4.classicalb.core.parser.analysis.checking.ClausesCheck;
import de.be4.classicalb.core.parser.analysis.checking.DefinitionCollector;
import de.be4.classicalb.core.parser.analysis.checking.DefinitionUsageCheck;
import de.be4.classicalb.core.parser.analysis.checking.IdentListCheck;
import de.be4.classicalb.core.parser.analysis.checking.PrimedIdentifierCheck;
import de.be4.classicalb.core.parser.analysis.checking.ProverExpressionsCheck;
import de.be4.classicalb.core.parser.analysis.checking.SemanticCheck;
import de.be4.classicalb.core.parser.analysis.checking.SemicolonCheck;
import de.be4.classicalb.core.parser.analysis.prolog.PrologExceptionPrinter;
import de.be4.classicalb.core.parser.analysis.prolog.RecursiveMachineLoader;
import de.be4.classicalb.core.parser.analysis.transforming.OpSubstitutions;
import de.be4.classicalb.core.parser.analysis.transforming.SyntaxExtensionTranslator;
import de.be4.classicalb.core.parser.exceptions.*;
import de.be4.classicalb.core.parser.lexer.LexerException;
import de.be4.classicalb.core.parser.node.EOF;
import de.be4.classicalb.core.parser.node.Start;
import de.be4.classicalb.core.parser.node.TIdentifierLiteral;
import de.be4.classicalb.core.parser.node.TPragmaFile;
import de.be4.classicalb.core.parser.node.Token;
import de.be4.classicalb.core.parser.parser.Parser;
import de.be4.classicalb.core.parser.parser.ParserException;
import de.be4.classicalb.core.parser.util.DebugPrinter;
import de.be4.classicalb.core.parser.util.Utils;
import de.be4.classicalb.core.parser.visualisation.ASTDisplay;
import de.be4.classicalb.core.parser.visualisation.ASTPrinter;
import de.prob.prolog.output.StructuredPrologOutput;
import de.prob.prolog.term.PrologTerm;

public class BParser {

	public static final String EXPRESSION_PREFIX = "#EXPRESSION";
	public static final String PREDICATE_PREFIX = "#PREDICATE";
	public static final String FORMULA_PREFIX = "#FORMULA";
	public static final String SUBSTITUTION_PREFIX = "#SUBSTITUTION";
	public static final String OPERATION_PATTERN_PREFIX = "#OPPATTERN";

	private IDefinitions definitions = new Definitions();
	private ParseOptions parseOptions;

	private List<String> doneDefFiles = new ArrayList<>();

	private final String fileName;
	private File directory;

	private IDefinitionFileProvider contentProvider;

	public static String getVersion() {
		Properties p = loadProperties();
		if (p != null) {
			return p.getProperty("version");
		} else {
			return "UNKNOWN";
		}
	}

	public static String getGitSha() {
		Properties p = loadProperties();
		if (p != null) {
			return p.getProperty("git");
		} else {
			return "UNKNOWN";
		}
	}

	private static Properties loadProperties() {
		Properties p = new Properties();
		try {
			p.load(BParser.class.getResourceAsStream("/bparser-build.properties"));
		} catch (Exception e) {
			return null;
		}
		return p;
	}

	public BParser() {
		this((String) null);
	}

	public BParser(final String fileName) {
		this.fileName = fileName;
		this.parseOptions = new ParseOptions();
	}

	public BParser(final String fileName, ParseOptions parseOptions) {
		this.fileName = fileName;
		this.parseOptions = parseOptions;
	}

	public IDefinitionFileProvider getContentProvider() {
		return contentProvider;
	}

	public static void printASTasProlog(final PrintStream out, final BParser parser, final File bfile, final Start tree,
			final ParsingBehaviour parsingBehaviour, IDefinitionFileProvider contentProvider)
			throws BCompoundException {
		final RecursiveMachineLoader rml = new RecursiveMachineLoader(bfile.getParent(), contentProvider,
				parsingBehaviour);
		rml.loadAllMachines(bfile, tree, parser.getDefinitions());
		rml.printAsProlog(new PrintWriter(out));
	}

	private static String getASTasFastProlog(final BParser parser, final File bfile, final Start tree,
			final ParsingBehaviour parsingBehaviour, IDefinitionFileProvider contentProvider)
			throws BCompoundException {
		final RecursiveMachineLoader rml = new RecursiveMachineLoader(bfile.getParent(), contentProvider,
				parsingBehaviour);
		rml.loadAllMachines(bfile, tree, parser.getDefinitions());
		StructuredPrologOutput structuredPrologOutput = new StructuredPrologOutput();
		rml.printAsProlog(structuredPrologOutput);
		Collection<PrologTerm> sentences = structuredPrologOutput.getSentences();
		StructuredPrologOutput output = new StructuredPrologOutput();

		output.openList();
		for (PrologTerm term : sentences) {
			output.printTerm(term);
		}
		output.closeList();
		output.fullstop();

		FastReadTransformer transformer = new FastReadTransformer(output);
		return transformer.write();
	}

	/**
	 * Parses the input file.
	 * 
	 * @see #parse(String, boolean, IFileContentProvider)
	 * @param machineFile
	 *            the machine file to be parsed
	 * @param verbose
	 *            print debug information
	 * @return the start AST node
	 * @throws IOException
	 *             if the file cannot be read
	 * @throws BCompoundException
	 *             if the file cannot be parsed
	 */
	public Start parseFile(final File machineFile, final boolean verbose) throws IOException, BCompoundException {
		contentProvider = new CachingDefinitionFileProvider();
		return parseFile(machineFile, verbose, contentProvider);
	}

	/**
	 * Parses the input file.
	 * 
	 * @see #parse(String, boolean)
	 * @param machineFile
	 *            the machine file to be parsed
	 * @param verbose
	 *            print debug information
	 * @param contentProvider
	 *            used to get the content of files
	 * @return the AST node
	 * @throws IOException
	 *             if the file cannot be read
	 * @throws BCompoundException
	 *             if the file cannot be parsed
	 */
	public Start parseFile(final File machineFile, final boolean verbose, final IFileContentProvider contentProvider)
			throws IOException, BCompoundException {
		this.directory = machineFile.getParentFile();
		if (verbose) {
			DebugPrinter.println("Parsing file '" + machineFile.getCanonicalPath() + "'");
		}
		String content = Utils.readFile(machineFile);
		return parse(content, verbose, contentProvider);
	}

	/**
	 * Use this method, if you only need to parse small inputs. This only gives
	 * the AST or an Exception, but no information about source positions. If
	 * you need those, call the instance method of BParser instead. Do NOT use
	 * this method to parse formulas, predicates and expression. Use the
	 * corresponding instance methods instead.
	 * 
	 * @param input
	 *            the B machine as input string
	 * @return AST of the input
	 * @throws BCompoundException
	 *             if the B machine can not be parsed
	 */
	public static Start parse(final String input) throws BCompoundException {
		BParser parser = new BParser("String Input");
		return parser.parse(input, false, new NoContentProvider());
	}

	public Start parseFormula(final String input) throws BCompoundException {
		final String theFormula = FORMULA_PREFIX + "\n" + input;
		return this.parse(theFormula, false, new NoContentProvider());
	}

	public Start parseExpression(final String input) throws BCompoundException {
		final String theFormula = EXPRESSION_PREFIX + "\n" + input;
		return this.parse(theFormula, false, new NoContentProvider());
	}

	public Start parseSubstitution(final String input) throws BCompoundException {
		final String theFormula = SUBSTITUTION_PREFIX + "\n" + input;
		return this.parse(theFormula, false, new NoContentProvider());
	}

	public Start parseTranstion(final String input) throws BCompoundException {
		final String theFormula = OPERATION_PATTERN_PREFIX + "\n" + input;
		return this.parse(theFormula, false, new NoContentProvider());
	}

	public Start parsePredicate(final String input) throws BCompoundException {
		final String theFormula = PREDICATE_PREFIX + "\n" + input;
		return this.parse(theFormula, false, new NoContentProvider());
	}

	public Start eparse(String input, IDefinitions context) throws BCompoundException, LexerException, IOException {
		final Reader reader = new StringReader(input);

		Start ast = null;

		List<String> ids = new ArrayList<>();

		final DefinitionTypes defTypes = new DefinitionTypes();
		defTypes.addAll(context.getTypes());

		BLexer bLexer = new BLexer(new PushbackReader(reader, BLexer.PUSHBACK_BUFFER_SIZE), defTypes);
		bLexer.setParseOptions(parseOptions);
		Token t;
		do {
			t = bLexer.next();
			if (t instanceof TIdentifierLiteral) {
				if (!ids.contains(t.getText())) {
					ids.add(t.getText());
				}
			}
		} while (!(t instanceof EOF));

		Parser p = new Parser(new EBLexer(input, BigInteger.ZERO, ids, defTypes));
		boolean ok;
		try {
			ast = p.parse();
			ok = true;
		} catch (Exception e) {
			handleException(e);
			ok = false;
		}

		BigInteger b = new BigInteger("2");
		b = b.pow(ids.size());
		b = b.subtract(BigInteger.ONE);

		while (!ok && b.compareTo(BigInteger.ZERO) > 0) {
			p = new Parser(new EBLexer(input, b, ids, defTypes));
			try {
				ast = p.parse();
				ok = true;
			} catch (ParserException e) {
				b = b.subtract(BigInteger.ONE);
				handleException(e);
			}
		}

		return ast;
	}

	private void handleException(Exception e) {
		// do nothing
	}

	/**
	 * Like {@link #parse(String, boolean, IFileContentProvider)}, but with
	 * {@link NoContentProvider} as last parameter, i.e., loading of referenced
	 * files is not enabled.
	 * 
	 * Use {@link #parse(String, boolean, IFileContentProvider)} instead to be
	 * able to control loading of referenced files.
	 * 
	 * @param input
	 *            the B machine as input string
	 * @param debugOutput
	 *            print debug information
	 * @return the AST node
	 * @throws BCompoundException
	 *             if the B machine cannot be parsed
	 */
	public Start parse(final String input, final boolean debugOutput) throws BCompoundException {
		return parse(input, debugOutput, new NoContentProvider());
	}

	/**
	 * Parses the input string.
	 * 
	 * @param input
	 *            The {@link String} to be parsed
	 * @param debugOutput
	 *            output debug messages on standard out?
	 * @param contentProvider
	 *            A {@link IFileContentProvider} that is able to load content of
	 *            referenced files during the parsing process. The content
	 *            provider is used for referenced definition files for example.
	 * @return the root node of the AST
	 * @throws BCompoundException
	 *             The {@link BCompoundException} class stores all
	 *             {@link BException}s occurred during the parsing process. The
	 *             {@link BException} class stores the actual exception as
	 *             delegate and forwards all method calls to it. So it is save
	 *             for tools to just use this exception if they want to extract
	 *             an error message. If the tools needs to extract additional
	 *             information, such as a source code position or involved
	 *             tokens respectively nodes, it needs to retrieve the delegate
	 *             exception. The {@link BException} class offers a
	 *             {@link BException#getCause()} method for this, which returns
	 *             the delegate exception.
	 *             <p>
	 *             Internal exceptions:
	 *             <ul>
	 *             <li>{@link PreParseException}: This exception contains errors
	 *             that occur during the preparsing. If possible it supplies a
	 *             token, where the error occurred.</li>
	 *             <li>{@link BLexerException}: If any error occurs in the
	 *             generated or customized lexer a {@link LexerException} is
	 *             thrown. Usually the lexer classes just throw a
	 *             {@link LexerException}. But this class unfortunately does not
	 *             contain any explicit information about the source code
	 *             position where the error occurred. Using aspect-oriented
	 *             programming we intercept the throwing of these exceptions to
	 *             replace them by our own exception. In our own exception we
	 *             provide the source code position of the last characters that
	 *             were read from the input.</li>
	 *             <li>{@link BParseException}: This exception is thrown in two
	 *             situations. On the one hand if the parser throws a
	 *             {@link ParserException} we convert it into a
	 *             {@link BParseException}. On the other hand it can be thrown
	 *             if any error is found during the AST transformations after
	 *             the parser has finished.</li>
	 *             <li>{@link CheckException}: If any problem occurs while
	 *             performing semantic checks, a {@link CheckException} is
	 *             thrown. We provide one or more nodes that are involved in the
	 *             problem. For example, if we find duplicate machine clauses,
	 *             we will list all occurrences in the exception.</li>
	 *             </ul>
	 */
	public Start parse(final String input, final boolean debugOutput, final IFileContentProvider contentProvider)
			throws BCompoundException {
		final Reader reader = new StringReader(input);
		try {
			// PreParsing
			final DefinitionTypes defTypes = preParsing(debugOutput, reader, contentProvider, directory);

			/*
			 * The definition types are used in the lexer in order to replace an
			 * identifier token by a definition call token. This is required if
			 * the definition is a predicate because an identifier can not be
			 * parsed as a predicate. For example "... SELECT def THEN ... "
			 * would yield to a parse error. The lexer will replace the
			 * identifier token "def" by a TDefLiteralPredicate which will be
			 * excepted by the parser
			 * 
			 */
			defTypes.addAll(definitions.getTypes());
			/*
			 * Main parser
			 */
			final BLexer lexer = new BLexer(new PushbackReader(reader, BLexer.PUSHBACK_BUFFER_SIZE), defTypes);
			lexer.setParseOptions(parseOptions);
			SabbleCCBParser parser = new SabbleCCBParser(lexer);
			final Start rootNode = parser.parse();

			final List<BException> bExceptionList = new ArrayList<>();
			/*
			 * Collect available definition declarations. Needs to be done now
			 * cause they are needed by the following transformations.
			 */
			final DefinitionCollector collector = new DefinitionCollector(defTypes, this.definitions);
			collector.collectDefinitions(rootNode);
			List<CheckException> definitionsCollectorExceptions = collector.getExceptions();
			for (CheckException checkException : definitionsCollectorExceptions) {
				bExceptionList.add(new BException(getFileName(), checkException));
			}

			// perfom AST transformations that can't be done by SableCC
			try {
				applyAstTransformations(rootNode);
			} catch (CheckException e) {
				throw new BCompoundException(new BException(getFileName(), e));
			}

			// perform some semantic checks which are not done in the parser
			List<CheckException> checkExceptions = performSemanticChecks(rootNode);
			for (CheckException checkException : checkExceptions) {
				bExceptionList.add(new BException(getFileName(), checkException));
			}
			if (!bExceptionList.isEmpty()) {
				throw new BCompoundException(bExceptionList);
			}
			return rootNode;
		} catch (final LexerException e) {
			throw new BCompoundException(new BException(getFileName(), e));
		} catch (final BParseException e) {
			throw new BCompoundException(new BException(getFileName(), e));
		} catch (final IOException e) {
			throw new BCompoundException(new BException(getFileName(), e));
		} catch (final PreParseException e) {
			throw new BCompoundException(new BException(getFileName(), e));
		} catch (final ParserException e) {
			final Token token = e.getToken();
			String msg = getImprovedErrorMessageBasedOnTheErrorToken(token);
			if (msg == null) {
				msg = e.getLocalizedMessage();
			}
			final String realMsg = e.getRealMsg();
			throw new BCompoundException(new BException(getFileName(), new BParseException(token, msg, realMsg, e)));
		} catch (BException e) {
			throw new BCompoundException(e);
		}
	}

	public String getFileName() {
		if (fileName == null) {
			return null;
		}
		File f = new File(fileName);
		if (f.exists()) {
			try {
				return f.getCanonicalPath();
			} catch (IOException e) {
				return fileName;
			}
		} else {
			return fileName;
		}
	}

	private String getImprovedErrorMessageBasedOnTheErrorToken(Token token) {
		if (token instanceof TPragmaFile) {
			return "A file pragma (/*@file ...*/) is not allowed here";
		}
		return null;
	}

	private DefinitionTypes preParsing(final boolean debugOutput, final Reader reader,
			final IFileContentProvider contentProvider, File directory)
			throws IOException, PreParseException, BException, BCompoundException {
		final PreParser preParser = new PreParser(new PushbackReader(reader, BLexer.PUSHBACK_BUFFER_SIZE),
				contentProvider, doneDefFiles, this.fileName, directory, parseOptions, this.definitions);
		preParser.setDebugOutput(debugOutput);
		preParser.parse();
		reader.reset();
		return preParser.getDefinitionTypes();
	}

	private void applyAstTransformations(final Start rootNode) throws CheckException {
		// default transformations
		OpSubstitutions.transform(rootNode, getDefinitions());
		rootNode.apply(new SyntaxExtensionTranslator());
		// more AST transformations?

	}

	private List<CheckException> performSemanticChecks(final Start rootNode) {
		final List<CheckException> list = new ArrayList<>();
		final SemanticCheck[] checks = { new ClausesCheck(), new SemicolonCheck(), new IdentListCheck(),
				new DefinitionUsageCheck(getDefinitions()), new PrimedIdentifierCheck(), new ProverExpressionsCheck() };
		// apply more checks?

		for (SemanticCheck check : checks) {
			check.setOptions(parseOptions);
			check.runChecks(rootNode);
			list.addAll(check.getCheckExceptions());
		}

		return list;
	}

	public IDefinitions getDefinitions() {
		return definitions;
	}

	public void setDefinitions(IDefinitions definitions) {
		this.definitions = definitions;
	}

	public static String getBuildRevision() {
		return Utils.getRevisionFromManifest();
	}

	public List<String> getDoneDefFiles() {
		return doneDefFiles;
	}

	public void setDoneDefFiles(final List<String> doneDefFiles) {
		this.doneDefFiles = doneDefFiles;
	}

	public ParseOptions getOptions() {
		return parseOptions;
	}

	public void setParseOptions(ParseOptions options) {
		this.parseOptions = options;
	}

	public int fullParsing(final File bfile, final ParsingBehaviour parsingBehaviour, final PrintStream out,
			final PrintStream err) {

		try {

			// Properties hashes = new Properties();

			// if (parsingBehaviour.getOutputFile() != null) {
			// if (hashesStillValid(parsingBehaviour.getOutputFile())){
			// return 0;
			// }
			// }

			final long start = System.currentTimeMillis();
			final Start tree = parseFile(bfile, parsingBehaviour.isVerbose());
			final long end = System.currentTimeMillis();

			if (parsingBehaviour.isPrintTime()) {
				out.println("Time for parsing: " + (end - start) + "ms");
			}

			if (parsingBehaviour.isPrintAST()) {
				ASTPrinter sw = new ASTPrinter(out);
				tree.apply(sw);
			}

			if (parsingBehaviour.isDisplayGraphically()) {
				tree.apply(new ASTDisplay());
			}

			final long start2 = System.currentTimeMillis();

			if (parsingBehaviour.isPrologOutput()) {
				printASTasProlog(out, this, bfile, tree, parsingBehaviour, contentProvider);
			}
			final long end2 = System.currentTimeMillis();

			if (parsingBehaviour.isPrintTime()) {
				out.println("Time for Prolog output: " + (end2 - start2) + "ms");
			}

			if (parsingBehaviour.isFastPrologOutput()) {
				try {
					String fp = getASTasFastProlog(this, bfile, tree, parsingBehaviour, contentProvider);
					out.println(fp);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		} catch (final IOException e) {
			if (parsingBehaviour.isPrologOutput()) {
				PrologExceptionPrinter.printException(err, e);
			} else {
				err.println();
				err.println("Error reading input file: " + e.getLocalizedMessage());
			}
			return -2;
		} catch (final BCompoundException e) {
			if (parsingBehaviour.isPrologOutput()) {
				PrologExceptionPrinter.printException(err, e, parsingBehaviour.isUseIndention(), false);
				// PrologExceptionPrinter.printException(err, e);
			} else {
				err.println();
				err.println("Error parsing input file: " + e.getLocalizedMessage());
			}
			return -3;
		}
		return 0;
	}

	@SuppressWarnings("unused")
	private boolean hashesStillValid(final File outputFile) {
		// File dir = outputFile.getParentFile();
		// Properties hashValues = readHashValues(outputFile, dir);
		// Set<Entry<Object, Object>> entrySet = hashValues.entrySet();
		// for (Entry<Object, Object> entry : entrySet) {
		// String file = (String) entry.getKey();
		// String hash = (String) entry.getValue();
		// File f = new File(dir + File.separator + file);
		// try {
		// if (!(f.exists() || FileDigest.sha(f).equals(hash)))
		// return false;
		// } catch (Exception e) {
		// return false;
		// }
		// }
		return false;
	}

	public void setDirectory(final File directory) {
		this.directory = directory;
	}

	// private Properties readHashValues(final File target, final File dir) {
	// String name = target.getName();
	// Properties p = new Properties();
	// String hashfile = name + ".hashes";
	// File hf = new File(dir.getAbsoluteFile() + File.separator + hashfile);
	// if (!hf.exists())
	// return p;
	// try {
	// p.load(new BufferedInputStream(new FileInputStream(hf)));
	// } catch (Exception e) {
	// // ignore
	// }
	// return p;
	// }

}

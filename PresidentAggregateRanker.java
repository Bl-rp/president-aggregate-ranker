package com.github.bl_rp.president_aggregate_ranker;

import java.awt.Desktop;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.Scanner;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.opencsv.CSVReader;

/**
 * This is a program for generating an aggregate to the polls at
 * <a href="https://en.wikipedia.org/wiki/Historical_rankings_of_presidents_of_the_United_States#Scholar_survey_results">English Wikipedia's
 * historical US president rankings</a>. The aggregate is generated by ranking presidents by the ratio of favourable to total pairwise comparisons
 * excluding ties. That is, for each poll a president is ranked in, each president ranked below him in the poll counts as a favourable comparison or
 * "victory" and each president ranked above him counts as an unfavourable comparison or "defeat". Presidents are then ranked by their score defined
 * as victories/(victories+defeats).
 * 
 * <p>The program also computes the correct quartile divisions for each individual poll as well as the aggregate, and prints the lowest rank in each
 * quartile (higher rank = higher quartile = lower number). The quartiles are defined by the 'median goes up' rule: the data is divided into top and
 * bottom halves which are divided into the first and last two quartiles, respectively, and in each split the median goes into the top half. Note:
 * ties are not accounted for when computing the quartiles. Only the total number of presidents in the poll is taken into account. This means if
 * there are 40 presidents in a poll so the first quartile would end at 10, but there are five presidents tied at rank 10, the program will still say
 * 10 so the first quartile has four too many presidents and the second has four too few. If you instead end the first quartile at rank 9 and put the
 * tied presidents in the second quartile, the first will have one too few and the second will have one too many, which is a better result, but these
 * considerations will have to be made by the user who notices a tie close to the end of a quartile.
 * 
 * <p>The aggregate is generated from the current table as a .csv file. To import the current table from Wikipedia, go to Google Sheets and enter
 * {@code =importHTML("https://en.wikipedia.org/wiki/Historical_rankings_of_presidents_of_the_United_States";"table";1)} into a cell, then download
 * as .csv.
 * 
 * <p>To run this program, download the .jar from the 'releases' tab of the project's
 * <a href="https://github.com/Bl-rp/president-aggregate-ranker">GitHub page</a>, and then run it. To run .jar files you need to have Java installed,
 * then open console (Command Prompt on Windows), go to the folder where the .jar is located and enter {@code java -jar JARNAME.jar arg1 arg2 arg3}
 * (you can have any number of arguments ({@code arg}) including none), where {@code JARNAME} is the name of the .jar file.
 * 
 * <p><b>Usage</b>: if the first argument is {@code --help}, prints short help text describing usage, then exits. If the first argument is
 * {@code --doc}, creates javadoc files in folder {@code JARNAME_doc} - where, again, {@code JARNAME} is the name of the .jar being run - within the
 * .jar's folder and opens the main class' documentation file in the default .html program, then exits; if the doc folder (or a file with that name)
 * already exists, prompts user for whether to empty it (or if it's a file, delete it) or cancel and exit unless there's a second argument which is
 * then taken as the answer which should be {@code y} if yes and anything else for no. If the first argument is not {@code --help} or {@code --doc},
 * first argument is taken as the path to the .csv file; if no arguments, path {@code US-president-rankings-table.csv} is taken as default, and if
 * the table is not found at this location, the user is prompted for the path. Second argument should be {@code y} if the table already has an
 * aggregate, and anything else otherwise; this is only used if no aggregate is found. If {@code y}, the program prints an error message and exits;
 * otherwise the program proceeds. If no second argument, the user is prompted for input as needed.
 * 
 * <p>The table is assumed to contain individual presidents in each row except the first and last, and individual polls in each column except the
 * first three and, if the table has an aggregate, the last. The first row should be a header and the last should display total number of presidents
 * ranked per poll. The first column should contain president numbers, the second should contain president names, the third should contain party
 * affiliations, and if an aggregate is present it should be found in the last column. The program checks for the following criteria and prints an
 * error message and exits if not all hold true:
 * 
 * <ul>
 *     <li>All rows have equal length.</li>
 *     <li>First row's first three entries are or start with "No.", "President", "Political party".</li>
 *     <li>First row's last entry is or starts with "Aggr." or user specifies that the table has no aggregate.</li>
 *     <li>Last row's second entry is or starts with "Total in survey".</li>
 *     <li>There is some string X such that entries that are not in the first or last row or the first three columns, or last column if the table 
 *     has an aggregate, are either integers optionally followed by " (tie)" or " *" or both, or identical to X (indicating 'not ranked').</li>
 * </ul>
 * 
 * <p>The program also checks that the 'Total in survey' numbers are correct and prints corrections if any are wrong, but doesn't exit.
 * 
 * <p><b>Dependency</b>: opencsv 3.9 (packaged into the .jar)
 * 
 * @author Joakim Andersson<br>
 * {@literal j.ason@live.se}<br>
 * Date: 2017-06-16
 */

public class PresidentAggregateRanker {
	private PresidentAggregateRanker() {} // so constructor doesn't show up in documentation
	
	private static class President implements Comparable<President> {
		// set aggregateScore to this to indicate that the president is not included in any polls and thus has 0 victories and defeats
		private static final RationalNumber noScore = new RationalNumber(-1,1);
		
		public final String name;
		public final String presidentNumber; // number in chronological order (e.g. George Washington's number is 01, Grover Cleveland's is 22/24)
		
		public int victories; // number of favourable pairwise comparisons across all polls
		public int defeats; // number of unfavourable pairwise comparisons across all polls
		// example: if A,B,C,D are ranked 1,2,3,4, then B has two victories and one defeat
		
		private RationalNumber aggregateScore; // "equals" victories/(victories+defeats)
		
		public String aggregateRank; // e.g. "05" or "14 (tie)"
		public boolean noRank = false; // true if aggregateScore == noScore
		public boolean tied = false; // true if aggregateScore is equal to some other president's score (i.e. compareTo returns 0)
		
		public President(String name, String presidentNumber) {
			this.name = name;
			this.presidentNumber = presidentNumber;
		}
		
		private void ensureAggregateScoreInitialized() { // also sets noRank
			if (aggregateScore == null)
				if (victories == 0 && defeats == 0) {
					aggregateScore = noScore;
					noRank = true;
				} else aggregateScore = new RationalNumber(victories, victories+defeats);
		}
		
		public Double getScore() {
			ensureAggregateScoreInitialized();
			return noRank ? null : aggregateScore.toDouble();
		}
		
		public int compareTo(President r) {
			// lower scoring president is higher in compareTo so Arrays.sort() yields descending sort by score. noScore is lowest score; sorted last
			ensureAggregateScoreInitialized();
			r.ensureAggregateScoreInitialized();
			
			int compare;
			if (aggregateScore == noScore)
				compare = r.aggregateScore == noScore ? 0 : 1;
			else if (r.aggregateScore == noScore)
				compare = -1;
			else
				compare = -aggregateScore.compareTo(r.aggregateScore);
			
			if (compare == 0)
				tied = r.tied = true;
			
			return compare;
		}
	}
	
	private static class RationalNumber implements Comparable<RationalNumber> {
		// the point of this class is to compare scores exactly with no rounding errors
		
		public final int numerator;
		public final int denominator;
		
		public RationalNumber(int numerator, int denominator) {
			if (denominator == 0)
				throw new IllegalArgumentException("denominator can't be 0");
			this.numerator = numerator;
			this.denominator = denominator;
		}
		
		public int compareTo(RationalNumber r) {
			int lhs = numerator * r.denominator;
			int rhs = r.numerator * denominator;
			return lhs == rhs ? 0 : (lhs > rhs ? 1 : -1);
		}
		
		public double toDouble() {
			return ((double)numerator)/denominator;
		}
	}
	
	private static String quartiles(int n) {
		/* returns a string of the lowest ranks (=highest numbers), separated by comma and space, in each quartile in a list of rankings from 1 to n.
		 * Quartiles are determined by 'median goes up'; see doc comment for details
		 */
		
		Integer[] quartiles = new Integer[4];
		boolean medianInTopHalf = true;
		
		if (n < 4) {
			// at least one quartile will be empty. For empty quartiles, quartiles[i] == null
			if (n < 1)
				throw new IllegalArgumentException("n should be positive: n = " + n);
			if (n == 1)
				quartiles[medianInTopHalf ? 0 : 3] = 1;
			else if (n == 2) {
				quartiles[medianInTopHalf ? 0 : 1] = 1;
				quartiles[medianInTopHalf ? 2 : 3] = 2;
			} else {
				quartiles[medianInTopHalf ? 0 : 1] = 1;
				quartiles[medianInTopHalf ? 1 : 2] = 2;
				quartiles[medianInTopHalf ? 2 : 3] = 3;
			}
		} else {
			int k = medianInTopHalf ? 1 : 0;
			quartiles[3] = n;
			quartiles[1] = (quartiles[3]+k)/2;
			quartiles[0] = (quartiles[1]+k)/2;
			quartiles[2] = quartiles[1] + (quartiles[3]-quartiles[1]+k)/2;
		}
		
		String toString = Arrays.toString(quartiles);
		return toString.substring(1, toString.length() - 1);
	}
	
	private static String rowAndColumnToCellName(int row, int column) {
		// top left cell (row == column == 0) is A1. Row names are positive integers. Column names go A B ... Z AA AB ... AZ BA ... ZZ AAA... 
		
		if (row < 0)
			throw new IllegalArgumentException("row should be nonnegative: " + row);
		if (column < 0)
			throw new IllegalArgumentException("column should be nonnegative: " + column);
		
		row++;
		column++;
		
		/* find numbers a_i, 1 <= a_i <= 26, s.t. column == 26^n a_n + ... + 26 a_1 + a_0. Convert to letters (1 -> a etc). Add to cellNameBuilder.
		 * a0 is added first; reverse to get column name. Add row name
		 */
		StringBuilder cellNameBuilder = new StringBuilder();
		while (column > 0) {
			int a = modNoZero(column, 26);
			cellNameBuilder.append(getLetterForNumber(a));
			column = (column-a)/26;
		}
		cellNameBuilder.reverse();
		cellNameBuilder.append(row);
		
		return cellNameBuilder.toString();
	}
	
	private static int modNoZero(int n, int b) {
		// n%b except it goes from 1 to b instead of 0 to b-1
		int mod = n % b;
		return mod == 0 ? b : mod;
	}
	
	private static char getLetterForNumber(int i) {
		// 1 -> a, 2 -> b, ..., z -> 26
		if (i < 1 || i > 26)
			throw new IllegalArgumentException("number should be between 1 and 26: " + i);
		return (char)(i + 64);
	}
	
	private static boolean cellValueStartsWith(String[][] table, int row, int column, String value) {
		/* used to check if certain cells have the expected values; otherwise print error message and exit (return from main method). startsWith()
		 * instead of equals() because there may be references
		 */
		if (table[row][column].startsWith(value))
			return true;
		wrongCellValue(table, row, column, value);
		return false;
	}
	
	private static void wrongCellValue(String[][] table, int row, int column, String value) {
		/* used to tell the user that a cell has an unexpected value (before exiting by returning from main method). startsWith() instead of equals()
		 * because there may be references
		 */
		System.out.println("ERROR: cell " + rowAndColumnToCellName(row, column) + " has value \"" + table[row][column] + "\", should be or start "
				+ "with \"" + value + "\".");
	}
	
	private static void emptyFolder(Path folder) throws IOException {
		// delete contents of folder
		for (Object file : Files.list(folder).toArray())
			deleteFile((Path)file);
	}
	
	private static void deleteFile(Path file) throws IOException {
		/* if file is symlink, delete it (not the symlink's target). If file is regular file, delete. If file is folder, delete contents without
		 * following symlinks (so nothing outside the folder is deleted), then delete folder
		 */
		
		if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS))
			Files.walkFileTree(file, new SimpleFileVisitor<Path>() {
			   @Override
			   public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
			       Files.delete(file);
			       return FileVisitResult.CONTINUE;
			   }
	
			   @Override
			   public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
			       Files.delete(dir);
			       return FileVisitResult.CONTINUE;
			   }
			});
		else
			Files.delete(file); // does not follow symlinks
	}
	
	private static Integer parseInteger(String s) {
		// copied from java.lang.Integer.parseInt(). Returns null for non-integer string instead of throwing exception
		int radix = 10;
		
		if (s == null)
			throw new NumberFormatException("null");
	
		int result = 0;
		boolean negative = false;
		int i = 0, len = s.length();
		int limit = -Integer.MAX_VALUE;
		int multmin;
		int digit;
	
		if (len > 0) {
			char firstChar = s.charAt(0);
			if (firstChar < '0') {
				if (firstChar == '-') {
					negative = true;
					limit = Integer.MIN_VALUE;
				} else if (firstChar != '+')
					return null;
	
				if (len == 1)
					return null;
				i++;
			}
			multmin = limit / radix;
			while (i < len) {
				digit = Character.digit(s.charAt(i++),radix);
				if (digit < 0)
					return null;
				if (result < multmin)
					return null;
				result *= radix;
				if (result < limit + digit)
					return null;
				result -= digit;
			}
		} else
			return null;
		return negative ? result : -result;
	}
	
	private static void createAndOpenJavadoc(String[] args) {
		/* create documentation files in folder JARNAME_doc where JARNAME.jar is the jar file's name, then open documentation for this class in the
		 * default .html program
		 */
		
		@SuppressWarnings("resource")
		Scanner sc = new Scanner(System.in);
		
		// get path to this class within the jar, as well as the path of the jar
		String pathInJar = PresidentAggregateRanker.class.getResource(PresidentAggregateRanker.class.getSimpleName() + ".class").getFile();
		URL resource = ClassLoader.getSystemClassLoader().getResource(pathInJar);
		if (resource == null) {
			System.out.println("Program does not appear to be run from a .jar file; exiting.");
			return;
		}
		String jarPathString = resource.getFile();
		jarPathString = jarPathString.substring(6, jarPathString.lastIndexOf('!'));
		
		boolean jarInitSuccess = false; // if initializing or closing ZipFile throws IOException, this tells us which one
		try (ZipFile jar = new ZipFile(jarPathString)) {
			jarInitSuccess = true;
			
			// get path of the doc directory we will create
			Path jarPath = Paths.get(jarPathString);
			String jarName = jarPath.getFileName().toString();
			int extIndex = jarName.lastIndexOf('.');
			if (extIndex != -1)
				jarName = jarName.substring(0, extIndex);
			Path docDirectory = jarPath.getParent().resolve(jarName + "_doc");
			boolean docDirectoryAlreadyExists = false;
			
			if (Files.exists(docDirectory, LinkOption.NOFOLLOW_LINKS)) {
				// if doc folder already exists, empty it (or if it's not a folder, delete it) or exit
				
				boolean isDirectory = Files.isDirectory(docDirectory, LinkOption.NOFOLLOW_LINKS);
				docDirectoryAlreadyExists = isDirectory;
					
				System.out.println((isDirectory ? "Doc folder already exists: " : "File already exists with doc folder's name: ") + docDirectory);
				boolean askToDelete = args.length < 2 || args[1] == null;
				boolean deleteDocs = askToDelete ? false : args[1].equals("y");
				if (askToDelete) {
					System.out.print((isDirectory ? "Delete doc folder contents?" : "Delete file?") + " (Otherwise exits.) If yes enter \"y\": ");
					deleteDocs = sc.nextLine().equalsIgnoreCase("y");
				}
				
				if (deleteDocs)
					try {
						if (isDirectory) {
							emptyFolder(docDirectory);
							System.out.println("Doc folder contents deleted.");
						} else {
							Files.delete(docDirectory);
							System.out.println("File deleted.");
						}
					} catch (IOException e) {
						System.out.println((isDirectory ? "ERROR: failed to delete doc folder contents." : "ERROR: failed to delete file.")
								+ " Error message:");
						e.printStackTrace();
						return;
					}
				else
					return;
			}
			
			if (!docDirectoryAlreadyExists) // create doc folder
				try {
					Files.createDirectory(docDirectory);
					System.out.println("Doc folder created: " + docDirectory);
				} catch (IOException e) {
					System.out.println("ERROR: doc folder creation failed: " + docDirectory);
					System.out.println("Error message:");
					e.printStackTrace();
					return;
				}
			
			// create documentation files
			try {
				for (ZipEntry entry : Collections.list(jar.entries())) {
					if (entry.getName().startsWith("doc/")) {
						String[] pathComponents = entry.getName().substring("doc/".length()).split("/");
						Path outputFile = Paths.get(docDirectory.toString(), pathComponents);
						
						// if entry is directory create entry, otherwise create entry's parent folder
						boolean entryIsDirectory = entry.isDirectory();
						Files.createDirectories(entryIsDirectory ? outputFile : outputFile.getParent());
						
						// write to file
						if (!entryIsDirectory)
							try (
									InputStream in = jar.getInputStream(entry);
									OutputStream out = new FileOutputStream(outputFile.toString());
							) {
								byte[] buffer = new byte[in.available()];
								in.read(buffer);
								out.write(buffer);
							}
					}
				}
			} catch (IOException e) {
				System.out.println("ERROR: failed to create documentation. Error message:");
				e.printStackTrace();
				return;
			}
			System.out.println("Documentation created.");
			
			// open documentation
			try {
				Path htmlPath = Paths.get(docDirectory.toString(), (pathInJar.substring(0, pathInJar.length() - "class".length()) + "html")
						.split("/"));
				Desktop.getDesktop().open(htmlPath.toFile());
			} catch (IOException e) {
				System.out.println("ERROR: failed to open documentation. Error message:");
				e.printStackTrace();
				return;
			}
			System.out.println("Documentation opened.");
			
		} catch (IOException e) { // this can happen during initialization or closing of ZipFile
			if (jarInitSuccess)
				System.out.println("ERROR: failed to close stream (you don't need to care about this). Error message:");
			else
				System.out.println("ERROR: failed to open .jar file for reading. Error message:");
			e.printStackTrace();
			return;
		}
	}
	
	/**
	 * @param args first argument (optional) is path to .csv file; second argument (optional) is {@code y} if table has aggregate (only used if
	 * aggregate is not found in table) and anything else otherwise. Alternatively, if first argument is {@code --help}, prints help text, or if
	 * {@code --doc}, creates javadoc files in folder {@code JARNAME_doc} where {@code JARNAME.jar} is the .jar file's name and opens the main class'
	 * documentation in the default .html program; if doc folder already exists (or file with that name), second argument (optional) is {@code y} if
	 * doc folder is to be emptied (or, if it's a file, it is to be deleted) before proceeding and anything else if program is to cancel and exit
	 */
	public static void main(String[] args) {
		System.out.println();
		
		if (args != null && args.length != 0 && args[0] != null) {
			
			if (args[0].equals("--help")) {
				System.out.println("This is a program for generating an aggregate to the polls at English Wikipedia's historical US president "
						+ "rankings, found here: "
						+ "https://en.wikipedia.org/wiki/Historical_rankings_of_presidents_of_the_United_States#Scholar_survey_results"
						+ "\n\n"
						+ "For more information, run the program with argument \"--doc\"."
						+ "\n\n"
						+ "USAGE: to run the program, enter \"java -jar JARNAME.jar arg1 arg2\" where JARNAME is the name of the .jar file and arg1 "
						+ "and arg2 are the (optional) arguments. If arg1 is \"--help\", the program prints help text and exits. If arg1 is "
						+ "\"--doc\", documentation files are created in the folder JARNAME_doc within the .jar file's folder, and the "
						+ "documentation is then opened in the default program for .html files (probably your web browser); the program then exits. "
						+ "If the doc folder already exists (or a file with that name) and arg2 is given, the folder will be emptied (or deleted if "
						+ "it's a file) if arg2 is \"y\" and exit otherwise; if arg2 is not given, the user will be prompted. If arg1 is not "
						+ "\"--help\" or \"--doc\", arg1 is taken as the path to the .csv file. If no arguments, the default path for the .csv file "
						+ "is \"US-president-rankings-table.csv\". If the file isn't found at this location, the user will be prompted for the "
						+ "path. arg2, if given, should be \"y\" if the table has an aggregate and anything else otherwise. It is only used if no "
						+ "aggregate is found in the table, and if not given, the user will be prompted at this point. If \"y\", the program prints "
						+ "an error message and exits; otherwise the program proceeds.");
				return;
			}
			
			if (args[0].equals("--doc")) {
				createAndOpenJavadoc(args);
				return;
			}
		}
		
		@SuppressWarnings("resource")
		Scanner sc = new Scanner(System.in);
		
		System.out.println("Run with argument --help or --doc for info and usage.\n\n");
		
		String defaultPath = "US-president-rankings-table.csv";
		boolean defaultPathUsed = args == null || args.length == 0 || args[0] == null;
		Path file = Paths.get(defaultPathUsed ? defaultPath : args[0]);
		Boolean tableHasAggregate = args == null || args.length < 2 || args[1] == null ? null : args[1].equalsIgnoreCase("y");
		
		boolean pathEntered = false;
		if (defaultPathUsed && !Files.exists(file)) { // if file isn't found at the default path
			System.out.print("Enter path to .csv file: ");
			file = Paths.get(sc.nextLine());
			pathEntered = true;
		}
		
		// read table as List<String[]> and convert to String[][]
		String[][] table;
		try (CSVReader reader = new CSVReader(new FileReader(file.toString()))) {
			table = reader.readAll().toArray(new String[0][]);
		} catch (FileNotFoundException e) {
			System.out.println("ERROR: .csv file not found at given location, is a directory, or cannot be opened for reading: " + file);
			System.out.println("Error message:");
			e.printStackTrace();
			return;
		} catch (IOException e) {
			System.out.println("ERROR: failed to read .csv file. Error message:");
			e.printStackTrace();
			return;
		}
		
		if (pathEntered)
			System.out.println("\n");
		
		int tableHeight = table.length;
		int tableWidth = table[0].length;
		
		// check that table is rectangle-shaped (all rows have equal length); otherwise print error message and exit
		for (String[] row : table)
			if (row.length != tableWidth) {
				System.out.println("ERROR: table is not rectangle-shaped.");
				return;
			}
		
		// check that some cells in the header and footer have the expected values; otherwise print error message and exit
		if (!cellValueStartsWith(table, 0, 0, "No.") || !cellValueStartsWith(table, 0, 1, "President")
				|| !cellValueStartsWith(table, 0, 2, "Political party") || !cellValueStartsWith(table, tableHeight-1, 1, "Total in survey"))
			return;
		
		// check that aggregate, if present, is in last column
		if (table[0][tableWidth-1].startsWith("Aggr.")) // last column in table is aggregate poll?
			tableHasAggregate = true;
		else {
			if (tableHasAggregate == null) {
				System.out.print("Aggregate poll not found. Table has aggregate? If yes enter \"y\": ");
				tableHasAggregate = sc.nextLine().equalsIgnoreCase("y");
				if (!tableHasAggregate)
					System.out.println("\n");
			}
			if (tableHasAggregate) { // if aggregate is present yet not in last column (or with unexpected title), print error message and exit
				wrongCellValue(table, 0, tableWidth-1, "Aggr.");
				return;
			}
		}
		
		int numPresidents = tableHeight - 2;
		int numPolls = tableWidth - 3 - (tableHasAggregate ? 1 : 0);
		int numPollsInclAggr = numPolls+1;
		
		if (numPresidents == 0 || numPolls == 0) {
			System.out.println("No " + (numPresidents == 0 ? "presidents" : "polls") + " found in the table.");
			return;
		}
				
		/* check that poll data is correctly formatted (integers, 'not ranked' etc - see doc comment) - otherwise print error message and exit - and
		 * save poll data as integers
		 */
		String tie = " (tie)"; // suffix string for some entries in the table
		String note = " *"; // suffix string for some entries in the table
		String tieRegex = Pattern.quote(tie);
		String noteRegex = Pattern.quote(note);
		String notRanked = null; // string that indicates 'not ranked' in the table
		Integer[][] pollData = new Integer[numPresidents][numPolls];
		for (int presidentIndex = 0; presidentIndex < numPresidents; presidentIndex++)
			for (int pollIndex = 0; pollIndex < numPolls; pollIndex++) {
				
				String entry = table[presidentIndex+1][pollIndex+3];				
				pollData[presidentIndex][pollIndex] = parseInteger(entry.replaceFirst(tieRegex, "").replaceFirst(noteRegex, ""));
				
				if (pollData[presidentIndex][pollIndex] == null) {
					if (notRanked == null)
						notRanked = entry;
					else if (!notRanked.equals(entry)) {
						System.out.println("ERROR: poll ranks that are not integer, optionally followed by \"" + tie + "\" or \"" + note + "\" or "
								+ "both, should all be identical (the value indicating 'not ranked'); two different values found: \"" + notRanked 
								+ "\", \"" + entry + "\".");
						return;
					}
				}
			}
		
		if (notRanked != null)
			System.out.println("String \"" + notRanked + "\" in table interpreted to indicate 'not ranked'.\n\n");
		
		// construct President array from president numbers and names
		President[] presidents = new President[numPresidents];
		for (int presidentIndex = 0; presidentIndex < numPresidents; presidentIndex++)
			presidents[presidentIndex] = new President(table[presidentIndex+1][1], table[presidentIndex+1][0]);
		
		// get poll names from table
		String[] pollNames = Arrays.copyOfRange(table[0], 3, 3+numPollsInclAggr);
		if (!tableHasAggregate)
			pollNames[numPollsInclAggr-1] = "Aggr.";
		
		// save number of presidents compared in each survey
		Integer[] totalInSurvey = new Integer[numPollsInclAggr];
		for (int i = 0; i < (tableHasAggregate ? numPollsInclAggr : numPolls); i++)
			totalInSurvey[i] = parseInteger(table[tableHeight-1][i+3]); // if not integer, parseInteger() returns null so makes no difference
		
		// compute number of victories and defeats for each president
		for (int president1Index = 0; president1Index < numPresidents; president1Index++) {
			for (int president2Index = president1Index+1; president2Index < numPresidents; president2Index++) {
				for (int pollIndex = 0; pollIndex < numPolls; pollIndex++) {
					// for all (unordered) pairs of presidents, iterate through all polls and compare their rankings
					
					if (pollData[president1Index][pollIndex] == null || pollData[president2Index][pollIndex] == null
							|| pollData[president1Index][pollIndex].equals(pollData[president2Index][pollIndex]))
						continue; // if not both are ranked, or they are tied
					
					if (pollData[president1Index][pollIndex] < pollData[president2Index][pollIndex]) {
						presidents[president1Index].victories++;
						presidents[president2Index].defeats++;
					} else {
						presidents[president1Index].defeats++;
						presidents[president2Index].victories++;
					}
				}
			}
		}
		
		// sort presidents by aggregate and set President.aggregateRank field
		President[] presidentsSortedByAggregate = presidents.clone();
		Arrays.sort(presidentsSortedByAggregate);
		for (int i = 0; i < numPresidents; i++) {
			
			if (presidentsSortedByAggregate[i].noRank)
				presidentsSortedByAggregate[i].aggregateRank = null;
			
			else {
				presidentsSortedByAggregate[i].aggregateRank = "" + (i+1);
				if (presidentsSortedByAggregate[i].aggregateRank.length() == 1)
					presidentsSortedByAggregate[i].aggregateRank = "0" + presidentsSortedByAggregate[i].aggregateRank;
				
				if (presidentsSortedByAggregate[i].tied)
					presidentsSortedByAggregate[i].aggregateRank += tie;
			}
		}
				
		// check that the survey totals in table are correct; otherwise print corrections and correct totalInSurvey
		boolean wrongTotalInSurveyFound = false;
		for (int pollIndex = 0; pollIndex < numPollsInclAggr; pollIndex++) {
			int count = 0;
			for (int j = 0; j < numPresidents; j++)
				if (pollIndex == numPollsInclAggr-1 ? !presidents[j].noRank : pollData[j][pollIndex] != null)
					count++;
			if (totalInSurvey[pollIndex] == null || totalInSurvey[pollIndex] != count) {
				if (!tableHasAggregate && pollIndex == numPollsInclAggr-1)
					System.out.println("Total in survey for \"" + pollNames[pollIndex] + "\" (aggregate) is " + count + ".");
				else
					System.out.println("Total in survey for poll \"" + pollNames[pollIndex] + "\" is incorrect; should be " + count + ".");
				totalInSurvey[pollIndex] = count;
				wrongTotalInSurveyFound = true;
			}
		}
		
		if (wrongTotalInSurveyFound)
			System.out.println("\n");
		
		// decide column widths for readable output
		int[] outputColumnWidth = new int[3];
		for (President p : presidents) {
			if (outputColumnWidth[0] < p.presidentNumber.length())
				outputColumnWidth[0] = p.presidentNumber.length();
			if (outputColumnWidth[1] < p.name.length())
				outputColumnWidth[1] = p.name.length();
			int aggregateRankLength = p.aggregateRank == null ? 4 : p.aggregateRank.length();
			if (outputColumnWidth[2] < aggregateRankLength)
				outputColumnWidth[2] = aggregateRankLength;
		}
		for (int i = 0; i < outputColumnWidth.length; i++)
			outputColumnWidth[i] += 5;
		
		System.out.println("Presidents sorted by number: number - name - rank - score");
		for (President p : presidents)
			System.out.printf("%-" + outputColumnWidth[0] + "s %-" + outputColumnWidth[1] + "s %-" + outputColumnWidth[2] + "s %s\n",
					p.presidentNumber, p.name, p.aggregateRank, p.getScore());
		
		System.out.println("\n");
		
		System.out.println("Presidents sorted by rank: number - name - rank - score");
		for (President p : presidentsSortedByAggregate)
			System.out.printf("%-" + outputColumnWidth[0] + "s %-" + outputColumnWidth[1] + "s %-" + outputColumnWidth[2] + "s %s\n",
					p.presidentNumber, p.name, p.aggregateRank, p.getScore());
		
		System.out.println("\n");
		
		System.out.println("Lowest rank in each quartile for each poll:");
		for (int i = 0; i < numPollsInclAggr; i++)
			System.out.println("\"" + pollNames[i] + "\": " + quartiles(totalInSurvey[i]));
	}
}

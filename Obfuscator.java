import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Простой лексический обфускатор Java-исходников.
 * 
 * <p>Осуществляет переименование пользовательских идентификаторов (классов, методов,
 * полей, переменных) в валидные, но непонятные имена вида {@code a, b, ..., aa, ab}.
 * Работает на уровне текста — без построения AST — с помощью регулярных выражений.
 * 
 * <h2>Ограничения</h2>
 * <ul>
 *   <li>Не обфусцирует имена из стандартной библиотеки, ключевых слов и импортированных классов.</li>
 *   <li>Может давать ложные срабатывания в строковых литералах или комментариях (в текущей реализации не фильтруются).</li>
 *   <li>Одноимённые идентификаторы в разных областях видимости получат одинаковое новое имя.</li>
 * </ul>
 * 
 * <h2>Использование</h2>
 * <pre>{@code
 * java Obfuscator Example.java Utils.java
 * }</pre>
 * 
 * @author Павел Бондаренко(Mikinich)
 * @version 1.1
 * @since 2025-12-07
 */
public class Obfuscator {

    /**
     * Приватный конструктор для предотвращения инстанцирования утилитного класса.
     * 
     * <p>Все методы класса статические — создание экземпляра бессмысленно и запрещено.
     */
    private Obfuscator() {
        throw new UnsupportedOperationException("Utility class — cannot be instantiated");
    }

    /**
     * Генерирует следующее уникальное имя для обфускации по схеме:
     * {@code a, b, ..., z, aa, ab, ..., az, ba, ...}.
     * 
     * <p>Реализует 26-ричную нумерацию с основанием {@code 'a'} без нулевого индекса.
     * Генератор детерминированный: при одинаковом начальном состоянии {@code counter}
     * выдаёт одинаковые последовательности.
     *
     * @param counter одномерный массив с текущим счётчиком (передаётся по ссылке для обновления)
     * @return новое имя, валидное как Java-идентификатор (начинается с буквы, состоит из строчных латинских букв)
     */
    private static String nextName(int[] counter) {
        int n = counter[0]++;
        StringBuilder sb = new StringBuilder();
        do {
            sb.append((char) ('a' + (n % 26)));
            n /= 26;
        } while (n > 0);
        return sb.reverse().toString();
    }

    /**
     * Множество всех зарезервированных ключевых слов Java.
     * Используется для фильтрации — такие слова никогда не переименовываются.
     */
    private static final Set<String> JAVA_KEYWORDS = new HashSet<>(Arrays.asList(
        "abstract","assert","boolean","break","byte","case","catch","char",
        "class","const","continue","default","do","double","else","enum",
        "extends","final","finally","float","for","goto","if","implements",
        "import","instanceof","int","interface","long","native","new","package",
        "private","protected","public","return","short","static","strictfp",
        "super","switch","synchronized","this","throw","throws","transient",
        "try","void","volatile","while","true","false","null"
    ));

    /**
     * Множество часто используемых классов и интерфейсов стандартной библиотеки Java.
     * Исключаются из переименования, чтобы сохранить читаемость вызовов вроде {@code System.out.println}.
     */
    private static final Set<String> STANDARD_CLASSES = new HashSet<>(Arrays.asList(
        "System", "Math", "String", "StringBuilder", "List", "ArrayList",
        "Map", "HashMap", "Set", "HashSet", "Iterator", "Files", "Paths",
        "Pattern", "Matcher", "IOException", "StandardCharsets", "Object",
        "Thread", "Runnable", "Exception", "RuntimeException", "Error",
        "Throwable", "Boolean", "Byte", "Character", "Double", "Float",
        "Integer", "Long", "Short", "Void", "Class", "ClassLoader",
        "Compiler", "Runtime", "SecurityManager", "StackTraceElement",
        "StrictMath", "Process", "ProcessBuilder", "StringBuffer",
        "Appendable", "CharSequence", "Cloneable", "Comparable", "Iterable",
        "AutoCloseable", "Enum", "PrintStream", "BufferedReader",
        "File", "FileInputStream", "FileOutputStream", "InputStream",
        "OutputStream", "Reader", "Writer", "Console", "Scanner"
    ));

    /**
     * Извлекает имена классов из объявленных {@code import}-ов.
     * 
     * <p>Поддерживает:
     * <ul>
     *   <li>Простые импорты: {@code import java.util.List;} → {@code List}</li>
     *   <li>Импорты по маске: {@code import java.util.*;} — не обрабатываются (нет группировки)</li>
     *   <li>Статические импорты: {@code import static java.lang.Math.*;} — частично (берутся имена частей пути)</li>
     * </ul>
     *
     * @param code текст Java-файла
     * @return множество имён, извлечённых из import-директив
     */
    private static Set<String> extractImportedClasses(String code) {
        Set<String> out = new HashSet<>();
        Pattern classPattern = Pattern.compile("\\bimport\\s+[^;]*\\.([A-Z]\\w*);?");
        Matcher classMatcher = classPattern.matcher(code);
        while (classMatcher.find()) {
            out.add(classMatcher.group(1));  
        }
        
        Pattern fullImportPattern = Pattern.compile("\\bimport\\s+([^;]+);");
        Matcher fullMatcher = fullImportPattern.matcher(code);
        while (fullMatcher.find()) {
            String importPath = fullMatcher.group(1);
            String[] parts = importPath.split("\\.");
            for (String part : parts) {
                if (part.length() > 0 && Character.isLowerCase(part.charAt(0))) {
                    out.add(part);
                }
            }
        }
        return out;
    }

    /**
     * Извлекает имена частей из объявления пакета (например, {@code com.example.utils} → {@code com, example, utils}).
     * Используется для исключения совпадений с именами пакетов.
     *
     * @param code текст Java-файла
     * @return множество имён компонентов пути пакета
     */
    private static Set<String> extractPackageNames(String code) {
        Set<String> out = new HashSet<>();
        Pattern pattern = Pattern.compile("\\bpackage\\s+([^;]+);");
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            String packagePath = matcher.group(1);
            String[] parts = packagePath.split("\\.");
            for (String part : parts) {
                if (part.length() > 0) {
                    out.add(part);
                }
            }
        }
        return out;
    }

    /**
     * Извлекает имя публичного класса из объявления {@code public class ...}.
     * Предполагается, что в файле не более одного {@code public class}.
     *
     * @param code текст Java-файла
     * @return имя публичного класса, либо {@code null}, если не найден
     */
    private static String extractPublicClassName(String code) {
        Pattern pattern = Pattern.compile("\\bpublic\\s+class\\s+(\\w+)");
        Matcher matcher = pattern.matcher(code);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Извлекает имена всех объявленных классов, интерфейсов и enum'ов в файле.
     *
     * @param code текст Java-файла
     * @return множество имён определённых типов
     */
    private static Set<String> extractClasses(String code) {
        Set<String> out = new HashSet<>();
        Pattern pattern = Pattern.compile("\\b(?:class|interface|enum)\\s+(\\w+)");
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) out.add(matcher.group(1));
        return out;
    }

    /**
     * Извлекает имена методов из их объявлений.
     * 
     * <p>Фильтрует по:
     * <ul>
     *   <li>Отсутствию в {@link #JAVA_KEYWORDS}</li>
     *   <li>Отсутствию в {@link #STANDARD_CLASSES}</li>
     * </ul>
     *
     * @param code текст Java-файла
     * @return множество имён методов, подлежащих обфускации
     */
    private static Set<String> extractMethodDeclarations(String code) {
        Set<String> out = new HashSet<>();
        Pattern pattern = Pattern.compile("\\b(?:public|private|protected|static|final|abstract|synchronized|native|strictfp|transient|volatile)*\\s+\\w+\\s+(\\w+)\\s*\\(");
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            String methodName = matcher.group(1);
            if (!JAVA_KEYWORDS.contains(methodName) && !STANDARD_CLASSES.contains(methodName)) {
                out.add(methodName);
            }
        }
        return out;
    }

    /**
     * Извлекает имена полей (членов класса).
     * Аналогично методам, фильтрует ключевые слова и стандартные имена.
     *
     * @param code текст Java-файла
     * @return множество имён полей
     */
    private static Set<String> extractFields(String code) {
        Set<String> out = new HashSet<>();
        Pattern pattern = Pattern.compile("\\b(?:public|private|protected|static|final|transient|volatile)*\\s+\\w+\\s+(\\w+)\\s*;");
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            String fieldName = matcher.group(1);
            if (!JAVA_KEYWORDS.contains(fieldName) && !STANDARD_CLASSES.contains(fieldName)) {
                out.add(fieldName);
            }
        }
        return out;
    }

    /**
     * Извлекает имена локальных переменных и параметров методов.
     * 
     * <p>Обрабатывает:
     * <ul>
     *   <li>Параметры: {@code void foo(int x, String y) { ... }}</li>
     *   <li>Объявления локальных переменных: {@code List<String> list = new ArrayList<>();}</li>
     * </ul>
     *
     * @param code текст Java-файла
     * @return множество имён переменных и параметров
     */
    private static Set<String> extractVariablesAndParameters(String code) {
        Set<String> out = new HashSet<>();
        
        Pattern paramPattern = Pattern.compile("\\b(?:public|private|protected|static|final|abstract|synchronized|native|strictfp|transient|volatile|\\w+)\\s+(?:\\w+|\\w+\\[\\s*\\w*\\s*\\]|\\w+\\.\\w+)\\s+(\\w+)\\s*(?=[,)])");
        Matcher paramMatcher = paramPattern.matcher(code);
        while (paramMatcher.find()) {
            String paramName = paramMatcher.group(1);
            if (!JAVA_KEYWORDS.contains(paramName) && !STANDARD_CLASSES.contains(paramName)) {
                out.add(paramName);
            }
        }
        
        Pattern varPattern = Pattern.compile("\\b(?:\\w+|\\w+\\[\\s*\\w*\\s*\\]|\\w+\\.\\w+)\\s+(\\w+)\\s*(?==|;|,|\\)|\\{|\\})");
        Matcher varMatcher = varPattern.matcher(code);
        while (varMatcher.find()) {
            String varName = varMatcher.group(1);
            if (!JAVA_KEYWORDS.contains(varName) && !STANDARD_CLASSES.contains(varName)) {
                out.add(varName);
            }
        }
        
        return out;
    }

    /**
     * Основной метод обработки одного Java-файла.
     * 
     * <p>Этапы:
     * <ol>
     *   <li>Чтение исходного кода</li>
     *   <li>Извлечение имён для потенциальной обфускации</li>
     *   <li>Фильтрация по множествам исключений (ключи, стандартные имена и т.д.)</li>
     *   <li>Генерация отображения {@code старое_имя → новое_имя}</li>
     *   <li>Последовательная замена всех вхождений с сортировкой по длине (чтобы избежать частичных замен)</li>
     *   <li>Запись результата в новый файл</li>
     * </ol>
     *
     * @param file путь к входному Java-файлу
     */
    private static void processFile(String file) {
        try {
            String code = Files.readString(Paths.get(file));

            String publicClass = extractPublicClassName(code);

            Set<String> importedClasses = extractImportedClasses(code);
            Set<String> packageNames = extractPackageNames(code);

            Set<String> ids = new HashSet<>();
            ids.addAll(extractClasses(code));
            ids.addAll(extractMethodDeclarations(code));
            ids.addAll(extractVariablesAndParameters(code));
            ids.addAll(extractFields(code));

            ids.removeAll(JAVA_KEYWORDS);
            ids.removeAll(STANDARD_CLASSES);
            ids.removeAll(importedClasses);
            ids.removeAll(packageNames);

            Set<String> standardMethods = new HashSet<>(Arrays.asList(
                "println", "print", "printf", "valueOf", "length", "charAt", 
                "substring", "indexOf", "contains", "equals", "equalsIgnoreCase",
                "toLowerCase", "toUpperCase", "trim", "split", "replace",
                "add", "remove", "get", "set", "size", "clear", "isEmpty",
                "put", "keySet", "values", "entrySet",
                "abs", "max", "min", "sqrt", "pow", "random", "ceil", "floor",
                "append", "toString", "reverse", "delete", "insert",
                "readAllLines", "write", "exists", "isDirectory", "list",
                "main", "start", "run", "wait", "notify", "notifyAll", "clone",
                "finalize", "hashCode", "getClass", "wait", "out", "in", "err",
                "find", "group", "matches", "replaceAll", "compile", "matcher"
            ));
            ids.removeAll(standardMethods);

            Set<String> localVars = extractLocalVariables(code);
            ids.removeAll(localVars);

            Set<String> obfuscatorParams = new HashSet<>(Arrays.asList(
                "counter", "code", "file", "ids", "rename", "result", "outFile", 
                "publicClass", "importedClasses", "packageNames", "sortedEntries"
            ));
            ids.removeAll(obfuscatorParams);

            int[] counter = {0};
            Map<String, String> rename = new HashMap<>();
            for (String id : ids) {
                rename.put(id, nextName(counter));
            }

            String result = code;
            List<Map.Entry<String, String>> sortedEntries = new ArrayList<>(rename.entrySet());
            sortedEntries.sort((a, b) -> b.getKey().length() - a.getKey().length());
            
            for (var e : sortedEntries) {
                result = result.replaceAll("\\b" + Pattern.quote(e.getKey()) + "\\b", e.getValue());
            }

            String newClass = publicClass == null ? null : rename.get(publicClass);

            String outFile;
            if (newClass == null) {
                outFile = file.replace(".java", "_obf.java");
            } else {
                outFile = newClass + ".java";
            }

            Files.writeString(Paths.get(outFile), result, StandardCharsets.UTF_8);
            System.out.println("Success: " + file + " -> " + outFile);

        } catch (IOException e) {
            System.err.println("Fail: " + file + ": " + e.getMessage());
        }
    }

    /**
     * Дополнительная фильтрация: исключает имена переменных, совпадающих с именами типов
     * из часто используемых I/O и утилитарных классов (например, {@code Pattern p}, {@code Matcher m}).
     * 
     * <p>Эвристика: помогает избежать переименования вроде {@code Pattern a; Matcher a;},
     * что нарушает компиляцию.
     *
     * @param code текст Java-файла
     * @return множество имён, которые, скорее всего, являются ссылками на стандартные типы
     */
    private static Set<String> extractLocalVariables(String code) {
        Set<String> out = new HashSet<>();
        
        Pattern localPattern = Pattern.compile("\\b(?:Pattern|Matcher|File|Path|BufferedReader|BufferedWriter|FileInputStream|FileOutputStream|PrintStream|Console|Scanner|Thread|Runnable|Process|ProcessBuilder)\\s+(\\w+)");
        Matcher localMatcher = localPattern.matcher(code);
        while (localMatcher.find()) {
            out.add(localMatcher.group(1));
        }
        
        return out;
    }

    /**
     * Точка входа в программу.
     * 
     * <p>Принимает один или несколько путей к Java-файлам и последовательно обрабатывает их.
     * 
     * <p>Пример:
     * <pre>{@code
     * java Obfuscator Calculator.java Parser.java
     * }</pre>
     *
     * @param args массив имён файлов для обфускации
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java Obfuscator <file1.java> [file2.java...]");
            return;
        }
        for (String f : args) processFile(f);
    }
}
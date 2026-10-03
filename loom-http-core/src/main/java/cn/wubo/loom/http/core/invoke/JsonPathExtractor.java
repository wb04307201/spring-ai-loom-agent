package cn.wubo.loom.http.core.invoke;

import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps Jayway JsonPath to extract values from a JSON response body.
 *
 * For each (varName, jsonPath) entry, evaluates the path against the body
 * and stores the result in the returned map keyed by varName.
 *
 * - Missing path (PathNotFoundException) -> value is {@code null}.
 * - Invalid JsonPath syntax (InvalidPathException) -> {@link ExtractionException}.
 */
public class JsonPathExtractor {

    /**
     * Extract values from the JSON body using the provided path map.
     *
     * @param body     the JSON response body
     * @param pathMap  mapping of result-key to JsonPath expression
     * @return map of result-key to extracted value (null for missing paths)
     * @throws ExtractionException when a JsonPath expression is syntactically invalid
     */
    public Map<String, Object> extract(String body, Map<String, String> pathMap) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : pathMap.entrySet()) {
            result.put(e.getKey(), evaluate(body, e.getValue()));
        }
        return result;
    }

    private Object evaluate(String body, String path) {
        try {
            return JsonPath.read(body, path);
        } catch (PathNotFoundException ex) {
            return null;
        } catch (InvalidPathException ex) {
            throw new ExtractionException("Invalid JsonPath: " + path, ex);
        }
    }
}
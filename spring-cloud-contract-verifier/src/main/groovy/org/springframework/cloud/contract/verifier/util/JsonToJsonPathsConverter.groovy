/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.contract.verifier.util

import java.util.function.Function
import java.util.regex.Matcher
import java.util.regex.Pattern

import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import com.toomuchcoding.jsonassert.JsonAssertion
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Commons

import org.springframework.cloud.contract.spec.internal.BodyMatcher
import org.springframework.cloud.contract.spec.internal.BodyMatchers
import org.springframework.cloud.contract.spec.internal.ExecutionProperty
import org.springframework.cloud.contract.spec.internal.MatchingType
import org.springframework.cloud.contract.spec.internal.OptionalProperty
import org.springframework.cloud.contract.spec.internal.RegexProperty
import org.springframework.cloud.contract.verifier.config.ContractVerifierConfigProperties
import org.springframework.util.SerializationUtils

/**
 * I would like to apologize to anyone who is reading this class. Since JSON is a hectic structure
 * this class is also hectic. The idea is to traverse the JSON structure and build a set of
 * JSON Paths together with methods needed to be called to build them.
 *
 * @author Marcin Grzejszczak
 * @author Tim Ysewyn
 * @author Olga Maciaszek-Sharma
 */
@Commons
class JsonToJsonPathsConverter {

	/**
	 * In case of issues with size assertion just provide this property as system property
	 * equal to "false" and then size assertion will be disabled
	 */
	private static final String SIZE_ASSERTION_SYSTEM_PROP = "spring.cloud.contract.verifier.assert.size"

	private static final Boolean SERVER_SIDE = false
	private static final Boolean CLIENT_SIDE = true
	private static final Pattern ANY_ARRAY_NOTATION_IN_JSONPATH = ~/\[(.*?)\]/
	private static final String DESCENDANT_OPERATOR = ".."

	private final boolean assertJsonSize

	JsonToJsonPathsConverter(boolean assertJsonSize) {
		this.assertJsonSize = assertJsonSize
	}

	JsonToJsonPathsConverter() {
		this(false)
		if (log.isTraceEnabled()) {
			log.trace("Creating JsonToJsonPaths converter with default properties")
		}
	}

	/**
	 * Removes from the parsed json any JSON path matching entries.
	 * That way we remain with values that should be checked in the auto-generated
	 * fashion.
	 *
	 * @param json - parsed JSON
	 * @param bodyMatchers - the part of request / response that contains matchers
	 * @return json with removed entries
	 */
	static def removeMatchingJsonPaths(def json, BodyMatchers bodyMatchers) {
		def jsonCopy = cloneBody(json)
		DocumentContext context = JsonPath.parse(jsonCopy)
		if (bodyMatchers?.hasMatchers()) {
			List<String> pathsToDelete = []
			List<String> paths = bodyMatchers.matchers().collect { it.path() }
			paths.each { String path ->
				try {
					def entry = entry(context, path)
					if (entry != null) {
						context.delete(path)
						pathsToDelete.add(path)
					}
				}
				catch (RuntimeException e) {
					if (log.isTraceEnabled()) {
						log.trace("Exception occurred while trying to delete path [${matcher.path()}]", e)
					}
				}
			}
			pathsToDelete.sort(Collections.reverseOrder())
			pathsToDelete.each {
				removeTrailingContainers(it, context)
			}
		}
		return jsonCopy
	}

	private static def entry(DocumentContext context, String path) {
		try {
			return context.read(path)
		}
		catch (Exception ex) {
			if (log.isTraceEnabled()) {
				log.trace("Exception occurred while trying to retrieve element via path [${path}]", ex)
			}
			return null
		}
	}

	/**
	 * Retrieves the value from JSON via json path
	 *
	 * @param json - parsed JSON
	 * @param jsonPath - json path
	 * @return matching part of the json
	 */
	static def readElement(def json, String jsonPath) {
		DocumentContext context = JsonPath.parse(json)
		return context.read(jsonPath)
	}

	/**
	 * Related to #391 and #1091 and #1414. The converted body looks different when done via the String notation than
	 * it does when done via a map notation. When working with String body and when matchers
	 * are provided, even when all entries of a map / list got removed, the map / list itself
	 * remains. That leads to unnecessary creation of checks for empty collection. With this method
	 * we're checking if the JSON path matcher is related to array checking and we're trying to
	 * remove that trailing collection. All in all it's better to use the Groovy based notation for
	 * defining body...
	 */
	private static boolean removeTrailingContainers(String matcherPath, DocumentContext context) {
		try {
			Matcher matcher = ANY_ARRAY_NOTATION_IN_JSONPATH.matcher(matcherPath)
			boolean containsArray = matcher.find()
			String pathWithoutAnyArray = containsArray ? matcherPath.
					substring(0, matcherPath.lastIndexOf(lastMatch(matcher))) : matcherPath
			def object = entry(context, pathWithoutAnyArray)
			// object got removed and it was the only element
			// let's get its parent and see if it contains an empty element
			if (isIterable(object)
					&&
					containsOnlyEmptyElements(object)
					&& isNotRootArray(matcherPath)) {
				String pathToDelete = pathToDelete(pathWithoutAnyArray)
				context.delete(pathToDelete)
				if (pathToDelete.contains(DESCENDANT_OPERATOR)) {
					Object root = context.read('$')
					if (rootContainsEmptyContainers(root)) {
						// now root contains only empty elements, we should remove the trailing containers
						context.delete('$[*]')
						return false
					}
					return false
				}
				return removeTrailingContainers(pathToDelete, context)
			}
			else {
				int lastIndexOfDot = matcherPath.lastIndexOf(".")
				if (lastIndexOfDot == -1) {
					return false
				}
				String lastParent = matcherPath.substring(0, lastIndexOfDot)
				def lastParentObject = context.read(lastParent)
				if (isIterable(lastParentObject)
						&&
						containsOnlyEmptyElements(lastParentObject)
						&& isNotRoot(lastParent)) {
					context.delete(lastParent)
					return removeTrailingContainers(lastParent, context)
				}
			}
			return false
		}
		catch (RuntimeException e) {
			if (log.isTraceEnabled()) {
				log.trace("Exception occurred while trying to delete path [${matcherPath}]", e)
			}
			return false
		}
	}

	private static boolean rootContainsEmptyContainers(root) {
		root instanceof Iterable && root.every { containsOnlyEmptyElements(it) }
	}

	private static String lastMatch(Matcher matcher) {
		List<String> matches = []
		while ({
			matches << matcher.group()
			matcher.find()
		}()) {
			continue
		}
		return matches[matches.size() - 1]
	}

	private static boolean isIterable(Object object) {
		return object instanceof Iterable || object instanceof Map
	}

	private static boolean isEmpty(Object object) {
		return isIterable(object) && object instanceof Iterable ? object.isEmpty() : ((Map) object).isEmpty()
	}

	private static String pathToDelete(String pathWithoutAnyArray) {
		// we can't remove root
		return pathWithoutAnyArray == '$' ? '$[*]' : pathWithoutAnyArray
	}

	private static boolean isNotRoot(String path) {
		// we can't remove root
		return path != '$'
	}

	private static boolean isNotRootArray(String path) {
		// we can't remove root
		return path != '$[*]'
	}

	private static boolean containsOnlyEmptyElements(Object object) {
		return object.every {
			if (it instanceof Map) {
				return it.isEmpty()
			}
			else if (it instanceof List) {
				return it.isEmpty()
			}
			return false
		}
	}

	// Doing a clone doesn't work for nested lists...
	private static Object cloneBody(Object object) {
		byte[] serializedObject = SerializationUtils.serialize(object)
		return SerializationUtils.deserialize(serializedObject)
	}

	/**
	 * For the given matcher converts it into a JSON path
	 * that checks the regex pattern or equality
	 *
	 * @param bodyMatcher
	 * @return JSON path that checks the regex for its last element
	 */
	static String convertJsonPathAndRegexToAJsonPath(BodyMatcher bodyMatcher, def body = null) {
		String path = bodyMatcher.path()
		Object value = bodyMatcher.value()
		if (value == null && bodyMatcher.matchingType() != MatchingType.EQUALITY
				&&
				bodyMatcher.matchingType() != MatchingType.TYPE) {
			return path
		}
		int lastIndexOfDot = lastIndexOfDot(path)
		String fromLastDot = path.substring(lastIndexOfDot + 1)
		String toLastDot = lastIndexOfDot == -1 ? '$' : path.substring(0, lastIndexOfDot)
		String propertyName = lastIndexOfDot == -1 ? '@' : "@.${fromLastDot}"
		String comparison = createComparison(propertyName, bodyMatcher, value, body)
		return "${toLastDot}[?(${comparison})]"
	}

	private static int lastIndexOfDot(String path) {
		if (pathContainsDotSeparatedKey(path)) {
			int lastIndexOfBracket = path.lastIndexOf("['")
			return path.substring(0, lastIndexOfBracket).lastIndexOf(".")
		}
		return path.lastIndexOf(".")
	}

	private static boolean pathContainsDotSeparatedKey(String path) {
		return path.contains("['")
	}


	@CompileStatic
	static Object generatedValueIfNeeded(Object value) {
		if (value instanceof RegexProperty) {
			return ((RegexProperty) value).generateAndEscapeJavaStringIfNeeded()
		}
		return value
	}

	private static String createComparison(String propertyName, BodyMatcher bodyMatcher, Object value, def body) {
		if (bodyMatcher.matchingType() == MatchingType.EQUALITY) {
			Object convertedBody = body
			if (!body) {
				throw new IllegalStateException("Body hasn't been passed")
			}
			try {
				convertedBody = MapConverter.transformValues(body) {
					return generatedValueIfNeeded(it)
				}
				Object retrievedValue = JsonPath.parse(convertedBody).
						read(bodyMatcher.path())
				String wrappedValue = retrievedValue instanceof Number ? retrievedValue : "'${retrievedValue.toString()}'"
				return "${propertyName} == ${wrappedValue}"
			}
			catch (PathNotFoundException e) {
				throw new IllegalStateException("Value [${bodyMatcher.path()}] not found in JSON [${JsonOutput.toJson(convertedBody)}]", e)
			}
		}
		else if (bodyMatcher.matchingType() == MatchingType.TYPE) {
			Integer min = bodyMatcher.minTypeOccurrence()
			Integer max = bodyMatcher.maxTypeOccurrence()
			String result = ""
			if (min != null) {
				result = "${propertyName}.size() >= ${min}"
			}
			if (max != null) {
				String maxResult = "${propertyName}.size() <= ${max}"
				result = result ? "${result} && ${maxResult}" : maxResult
			}
			return result
		}
		else {
			String convertedValue = value.toString().replace('/', '\\\\/')
			return "${propertyName} =~ /(${convertedValue})/"
		}
	}

	JsonPaths transformToJsonPathWithTestsSideValues(def json, Function parsingClosure, boolean includeEmptyCheck) {
		return transformToJsonPathWithValues(json, SERVER_SIDE, { parsingClosure.apply(it) }, includeEmptyCheck)
	}

	JsonPaths transformToJsonPathWithTestsSideValues(def json,
			Closure parsingClosure = MapConverter.JSON_PARSING_CLOSURE,
			boolean includeEmptyCheck = false) {
		return transformToJsonPathWithValues(json, SERVER_SIDE, parsingClosure, includeEmptyCheck)
	}

	JsonPaths transformToJsonPathWithStubsSideValues(def json,
			Closure parsingClosure = MapConverter.JSON_PARSING_CLOSURE,
			boolean includeEmptyCheck = false) {
		return transformToJsonPathWithValues(json, CLIENT_SIDE, parsingClosure, includeEmptyCheck)
	}

	static JsonPaths transformToJsonPathWithStubsSideValuesAndNoArraySizeCheck(def json,
			Closure parsingClosure = MapConverter.JSON_PARSING_CLOSURE) {
		return new JsonToJsonPathsConverter()
				.transformToJsonPathWithValues(json, CLIENT_SIDE, parsingClosure)
	}

	private JsonPaths transformToJsonPathWithValues(def json, boolean clientSide,
			Closure parsingClosure = MapConverter.JSON_PARSING_CLOSURE,
			boolean includeEmptyCheck = false) {
		if (json == null || (!json && !includeEmptyCheck)) {
			return new JsonPaths()
		}
		Object convertedJson = MapConverter.
				getClientOrServerSideValues(json, clientSide, parsingClosure)
		Object jsonWithPatterns = ContentUtils.
				convertDslPropsToTemporaryRegexPatterns(convertedJson, parsingClosure)
		MethodBufferingJsonVerifiable methodBufferingJsonPathVerifiable =
				new DelegatingJsonVerifiable(JsonAssertion.
						assertThat(JsonOutput.toJson(jsonWithPatterns))
														  .withoutThrowingException())
		JsonPaths pathsAndValues = [] as Set
		if (isRootElement(methodBufferingJsonPathVerifiable) && !json) {
			pathsAndValues.add(methodBufferingJsonPathVerifiable.isEmpty())
			return pathsAndValues
		}
		traverseRecursivelyForKey(jsonWithPatterns, methodBufferingJsonPathVerifiable,
				{ MethodBufferingJsonVerifiable key, Object value ->
					if (value instanceof ExecutionProperty || !(key instanceof FinishedDelegatingJsonVerifiable)) {
						return
					}
					pathsAndValues.add(key)
				}, parsingClosure)
		return pathsAndValues
	}

	protected def traverseRecursively(Class parentType, MethodBufferingJsonVerifiable key, def value,
			Closure closure, Closure parsingClosure = MapConverter.JSON_PARSING_CLOSURE) {
		value = ContentUtils.returnParsedObject(value)
		if (value instanceof String && value) {
			try {
				def json = parsingClosure(value)
				if (json instanceof Map) {
					return convertWithKey(parentType, key, json, closure, parsingClosure)
				}
			}
			catch (Exception ignore) {
				return runClosure(closure, key, value)
			}
		}
		else if (isAnEntryWithNonCollectionLikeValue(value)) {
			return convertWithKey(List, key, value as Map, closure, parsingClosure)
		}
		else if (isAnEntryWithoutNestedStructures(value)) {
			return convertWithKey(List, key, value as Map, closure, parsingClosure)
		}
		else if (value instanceof Map && !value.isEmpty()) {
			return convertWithKey(Map, key, value as Map, closure, parsingClosure)
		}
		else if (value instanceof Map && value.isEmpty()) {
			return runClosure(closure, key.isEmpty(), value)
			// JSON with a list of primitives ["a", "b", "c"] in root issue #266
		}
		else if (key.isIteratingOverNamelessArray() && value instanceof List
				&&
				listContainsOnlyPrimitives(value)) {
			addSizeVerificationForListWithPrimitives(key, closure, value)
			value.each {
				traverseRecursively(Object, key.arrayField().
						contains(ContentUtils.returnParsedObject(it)),
						ContentUtils.returnParsedObject(it), closure, parsingClosure)
			}
			// JSON containing list of primitives { "partners":[ { "role":"AGENT", "payment_methods":[ "BANK", "CASH" ]	} ]
		}
		else if (value instanceof List && listContainsOnlyPrimitives(value)) {
			addSizeVerificationForListWithPrimitives(key, closure, value)
			value.each {
				traverseRecursively(Object,
						valueToAsserter(key.arrayField(), ContentUtils.
								returnParsedObject(it)),
						ContentUtils.returnParsedObject(it), closure, parsingClosure)
			}
		}
		else if (value instanceof List && !value.empty) {
			MethodBufferingJsonVerifiable jsonPathVerifiable =
					createAsserterFromList(key, value)
			addSizeVerificationForListWithPrimitives(key, closure, value)
			value.each { def element ->
				traverseRecursively(List,
						createAsserterFromListElement(jsonPathVerifiable, ContentUtils.
								returnParsedObject(element)),
						ContentUtils.returnParsedObject(element), closure, parsingClosure)
			}
			return value
		}
		else if (value instanceof List && value.empty) {
			return runClosure(closure, key, value)
		}
		else if (key.isIteratingOverArray()) {
			traverseRecursively(Object, key.arrayField().
					contains(ContentUtils.returnParsedObject(value)),
					ContentUtils.returnParsedObject(value), closure, parsingClosure)
		}
		try {
			return runClosure(closure, key, value)
		}
		catch (Exception ignore) {
			return value
		}
	}

	// Size verification: https://github.com/Codearte/accurest/issues/279
	private void addSizeVerificationForListWithPrimitives(MethodBufferingJsonVerifiable key, Closure closure, List value) {
		String systemPropValue = System.getProperty(SIZE_ASSERTION_SYSTEM_PROP)
		Boolean configPropValue = assertJsonSize
		if ((systemPropValue != null && Boolean.parseBoolean(systemPropValue))
				||
				configPropValue && listContainsOnlyPrimitives(value)) {
			addArraySizeCheck(key, value, closure)
		}
		else {
			if (log.isTraceEnabled()) {
				log.trace("Turning off the incubating feature of JSON array check. "
						+
						"System property [$systemPropValue]. Config property [$configPropValue]")
			}
			return
		}
	}

	private void addArraySizeCheck(MethodBufferingJsonVerifiable key, List value, Closure closure) {
		if (log.isDebugEnabled()) {
			log.debug("WARNING: Turning on the incubating feature of JSON array check")
		}
		if (isRootElement(key) || key.assertsConcreteValue()) {
			if (value.size() > 0) {
				closure(key.hasSize(value.size()), value)
			}
		}
	}

	private boolean isRootElement(MethodBufferingJsonVerifiable key) {
		return key.jsonPath() == '$'
	}

	// If you have a list of not-only primitives it can contain different sets of elements (maps, lists, primitives)
	private MethodBufferingJsonVerifiable createAsserterFromList(MethodBufferingJsonVerifiable key, List value) {
		if (key.isIteratingOverNamelessArray()) {
			return key.array()
		}
		else if (key.isIteratingOverArray() && isAnEntryWithLists(value)) {
			if (!value.every { listContainsOnlyPrimitives(it as List) }) {
				return key.array()
			}
			else {
				return key.iterationPassingArray()
			}
		}
		else if (key.isIteratingOverArray()) {
			return key.iterationPassingArray()
		}
		return key
	}

	private MethodBufferingJsonVerifiable createAsserterFromListElement(MethodBufferingJsonVerifiable jsonPathVerifiable, def element) {
		if (jsonPathVerifiable.isAssertingAValueInArray()) {
			def object = ContentUtils.returnParsedObject(element)
			if (object instanceof Pattern) {
				return jsonPathVerifiable.matches((object as Pattern).pattern())
			}
			return jsonPathVerifiable.contains(object)
		}
		else if (element instanceof List) {
			if (listContainsOnlyPrimitives(element)) {
				return jsonPathVerifiable.array()
			}
		}
		return jsonPathVerifiable
	}

	private def runClosure(Closure closure, MethodBufferingJsonVerifiable key, def value) {
		if (key.
				isAssertingAValueInArray()
				&& !(value instanceof List || value instanceof Map)) {
			return closure(valueToAsserter(key, value), value)
		}
		return closure(key, value)
	}

	private boolean isAnEntryWithNonCollectionLikeValue(def value) {
		if (!(value instanceof Map)) {
			return false
		}
		Map valueAsMap = ((Map) value)
		boolean mapHasOneEntry = valueAsMap.size() == 1
		if (!mapHasOneEntry) {
			return false
		}
		Object valueOfEntry = valueAsMap.entrySet().first().value
		return !(valueOfEntry instanceof Map || valueOfEntry instanceof List)
	}

	private boolean isAnEntryWithoutNestedStructures(def value) {
		if (!(value instanceof Map)) {
			return false
		}
		Map valueAsMap = ((Map) value)
		if (valueAsMap.isEmpty()) {
			return false
		}
		return valueAsMap.entrySet().every { Map.Entry entry ->
			[String, Number, Boolean].any { it.isAssignableFrom(entry.value.getClass()) }
		}
	}

	private boolean listContainsOnlyPrimitives(List list) {
		if (list.empty) {
			return false
		}
		return list.every { def element ->
			[String, Number, Boolean].any {
				it.isAssignableFrom(element.getClass())
			}
		}
	}

	private boolean isAnEntryWithLists(def value) {
		if (!(value instanceof Iterable)) {
			return false
		}
		return value.every { def entry ->
			entry instanceof List
		}
	}

	private Map convertWithKey(Class parentType, MethodBufferingJsonVerifiable parentKey, Map map,
			Closure closureToExecute, Closure parsingClosure) {
		return map.collectEntries {
			Object entrykey, value ->
				def convertedValue = ContentUtils.returnParsedObject(value)
				[entrykey, traverseRecursively(parentType,
						convertedValue instanceof List ?
								list(convertedValue, entrykey, parentKey) :
								convertedValue instanceof Map ? parentKey.
										field(new ShouldTraverse(entrykey)) :
										valueToAsserter(parentKey.field(entrykey), convertedValue)
						, convertedValue, closureToExecute, parsingClosure)]
		}
	}

	protected MethodBufferingJsonVerifiable list(List convertedValue, Object entrykey, MethodBufferingJsonVerifiable parentKey) {
		if (convertedValue.empty) {
			return parentKey.array(entrykey).isEmpty()
		}
		return listContainsOnlyPrimitives(convertedValue) ?
				parentKey.arrayField(entrykey) :
				parentKey.array(entrykey)
	}

	private void traverseRecursivelyForKey(def json, MethodBufferingJsonVerifiable rootKey,
			Closure closure, Closure parsingClosure = MapConverter.JSON_PARSING_CLOSURE) {
		traverseRecursively(Map, rootKey, json, closure, parsingClosure)
	}

	protected MethodBufferingJsonVerifiable valueToAsserter(MethodBufferingJsonVerifiable key, Object value) {
		def convertedValue = ContentUtils.returnParsedObject(value)
		if (key instanceof FinishedDelegatingJsonVerifiable) {
			return key
		}
		if (convertedValue instanceof Pattern) {
			return key.matches((convertedValue as Pattern).pattern())
		}
		else if (convertedValue instanceof OptionalProperty) {
			return key.matches((convertedValue as OptionalProperty).optionalPattern())
		}
		else if (convertedValue instanceof GString) {
			return key.
					matches(RegexpBuilders.buildGStringRegexpForTestSide(convertedValue))
		}
		else if (convertedValue instanceof ExecutionProperty) {
			return key
		}
		return key.isEqualTo(convertedValue)
	}

}

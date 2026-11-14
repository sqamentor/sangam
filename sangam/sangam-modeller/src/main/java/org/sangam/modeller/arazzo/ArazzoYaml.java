// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.arazzo;

import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * The YAML form of an Arazzo (or OpenAPI) document as a JSON tree (owner decision 204). SnakeYAML is used with a
 * {@link SafeConstructor}, and only to compose the node graph: no Java object is ever constructed from the document.
 * The tree follows the JSON data model, so the importer checks a YAML document exactly as its JSON form:
 * <ul>
 *   <li>one document, UTF-8 only, at most the importer's byte limit and 200 levels deep;</li>
 *   <li>anchors, aliases and merge keys are refused (no shared or expanded subtrees);</li>
 *   <li>quoted scalars are strings; plain scalars follow the YAML 1.2 JSON-compatible forms: {@code null}/{@code ~}/empty,
 *       {@code true}/{@code false}, decimal integers, decimal numbers (kept exact as BigDecimal, never binary floating
 *       point); every other plain scalar is a string, except YAML 1.1 forms that would change meaning silently
 *       ({@code yes}/{@code no}/{@code on}/{@code off}, {@code 0x}/{@code 0o} and leading-zero (octal) numbers,
 *       {@code .inf}, {@code .nan}, sexagesimal numbers such as {@code 10:30}, underscores in numbers), which are
 *       refused with a request to quote them;</li>
 *   <li>the standard tags {@code !!str}, {@code !!int}, {@code !!float}, {@code !!bool}, {@code !!null}, {@code !!map},
 *       {@code !!seq} are honoured; every other tag is refused;</li>
 *   <li>mapping keys are scalars, unique, read as text.</li>
 * </ul>
 */
final class ArazzoYaml {

    static final int MAX_DEPTH = 200;
    static final int MAX_NUMBER_LENGTH = 100;

    private static final Pattern INT = Pattern.compile("[-+]?[0-9]+");
    private static final Pattern DECIMAL = Pattern.compile("[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?");
    private static final Pattern NULL = Pattern.compile("~|null|Null|NULL|");
    private static final Pattern BOOL = Pattern.compile("true|True|TRUE|false|False|FALSE");
    /** YAML 1.1 implicit forms that a 1.1 reader takes as booleans or numbers: refused, never guessed. */
    private static final Pattern YAML11 = Pattern.compile(
            "y|Y|yes|Yes|YES|n|N|no|No|NO|on|On|ON|off|Off|OFF"
                    + "|[-+]?0x[0-9a-fA-F_]+|[-+]?0o?[0-7_]+|[-+]?0b[01_]+"
                    + "|[-+]?\\.(inf|Inf|INF)|\\.(nan|NaN|NAN)|[-+]?[0-9][0-9_]*(:[0-5]?[0-9])+(\\.[0-9_]*)?"
                    + "|[-+]?([0-9][0-9_]*)?\\.[0-9_]*([eE][-+][0-9]+)?|[-+]?[0-9][0-9_]+");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Resolver RESOLVER = new Resolver();

    /** A YAML document that is not read, with the line it was found at (1-based, 0 when unknown). */
    static final class YamlProblem extends Exception {
        final int line;

        YamlProblem(String message, Node at) {
            super(message);
            this.line = at == null || at.getStartMark() == null ? 0 : at.getStartMark().getLine() + 1;
        }
    }

    private ArazzoYaml() {
    }

    /** The document as a JSON tree; {@code maxBytes} bounds the input (code points are checked again by SnakeYAML). */
    static JsonNode read(byte[] bytes, int maxBytes) throws YamlProblem {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new YamlProblem("the YAML document is not valid UTF-8", null);
        }
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(MAX_DEPTH);
        options.setCodePointLimit(Math.max(1, maxBytes));
        options.setTagInspector(tag -> false);
        DumperOptions dump = new DumperOptions();
        Yaml yaml = new Yaml(new SafeConstructor(options), new Representer(dump), dump, options);
        Node root;
        try {
            root = yaml.compose(new StringReader(text));
        } catch (YAMLException e) {
            throw new YamlProblem("not a readable YAML document: " + e.getMessage(), null);
        }
        if (root == null) {
            throw new YamlProblem("the YAML document is empty", null);
        }
        return convert(root, 0, new HashSet<>());
    }

    private static JsonNode convert(Node node, int depth, Set<Node> seen) throws YamlProblem {
        if (depth > MAX_DEPTH) {
            throw new YamlProblem("nested deeper than " + MAX_DEPTH + " levels", node);
        }
        if (node.getAnchor() != null || !seen.add(node)) {
            throw new YamlProblem("anchors and aliases are not read; write the value out", node);
        }
        return switch (node.getNodeId()) {
            case scalar -> scalar((ScalarNode) node);
            case sequence -> {
                requireTag(node, Tag.SEQ);
                ArrayNode a = F.arrayNode();
                for (Node item : ((SequenceNode) node).getValue()) {
                    a.add(convert(item, depth + 1, seen));
                }
                yield a;
            }
            case mapping -> {
                requireTag(node, Tag.MAP);
                ObjectNode o = F.objectNode();
                for (NodeTuple t : ((MappingNode) node).getValue()) {
                    if (!(t.getKeyNode() instanceof ScalarNode k)) {
                        throw new YamlProblem("a mapping key must be a scalar", t.getKeyNode());
                    }
                    if (k.getAnchor() != null || Tag.MERGE.equals(k.getTag())) {
                        throw new YamlProblem("merge keys, anchors and aliases are not read", k);
                    }
                    String key = k.getValue();
                    if (o.has(key)) {
                        throw new YamlProblem("duplicate key " + key, k);
                    }
                    o.set(key, convert(t.getValueNode(), depth + 1, seen));
                }
                yield o;
            }
            default -> throw new YamlProblem("unsupported YAML node " + node.getNodeId(), node);
        };
    }

    private static void requireTag(Node node, Tag expected) throws YamlProblem {
        if (!expected.equals(node.getTag())) {
            throw new YamlProblem("tag " + node.getTag() + " is not read", node);
        }
    }

    private static JsonNode scalar(ScalarNode s) throws YamlProblem {
        String v = s.getValue();
        Tag implicit = RESOLVER.resolve(NodeId.scalar, v, s.isPlain());
        Tag tag = s.getTag();
        if (tag.equals(implicit)) {
            return s.isPlain() ? plain(v, s) : F.textNode(v);
        }
        // an explicit standard tag
        if (Tag.STR.equals(tag)) {
            return F.textNode(v);
        }
        if (Tag.NULL.equals(tag) && NULL.matcher(v).matches()) {
            return F.nullNode();
        }
        if (Tag.BOOL.equals(tag) && BOOL.matcher(v).matches()) {
            return F.booleanNode(v.equalsIgnoreCase("true"));
        }
        if (Tag.INT.equals(tag) && INT.matcher(v).matches()) {
            return integer(v, s);
        }
        if (Tag.FLOAT.equals(tag) && DECIMAL.matcher(v).matches()) {
            return decimal(v, s);
        }
        throw new YamlProblem("tag " + tag + " with value " + v + " is not read", s);
    }

    private static JsonNode plain(String v, ScalarNode s) throws YamlProblem {
        if (NULL.matcher(v).matches()) {
            return F.nullNode();
        }
        if (BOOL.matcher(v).matches()) {
            return F.booleanNode(v.equalsIgnoreCase("true"));
        }
        String digits = v.startsWith("+") || v.startsWith("-") ? v.substring(1) : v;
        if (INT.matcher(v).matches() && !(digits.length() > 1 && digits.startsWith("0"))) {
            return integer(v, s);
        }
        if (DECIMAL.matcher(v).matches() && !INT.matcher(v).matches()) {
            return decimal(v, s);
        }
        if (INT.matcher(v).matches() || YAML11.matcher(v).matches()) {
            throw new YamlProblem("plain scalar " + v + " means a boolean or number in YAML 1.1 and text in 1.2;"
                    + " quote it", s);
        }
        return F.textNode(v);
    }

    private static JsonNode integer(String v, ScalarNode s) throws YamlProblem {
        if (v.length() > MAX_NUMBER_LENGTH) {
            throw new YamlProblem("number longer than " + MAX_NUMBER_LENGTH + " characters", s);
        }
        BigInteger i = new BigInteger(v.startsWith("+") ? v.substring(1) : v);
        if (i.bitLength() < 32) {
            return F.numberNode(i.intValue());
        }
        if (i.bitLength() < 64) {
            return F.numberNode(i.longValue());
        }
        return F.numberNode(i);
    }

    private static JsonNode decimal(String v, ScalarNode s) throws YamlProblem {
        if (v.length() > MAX_NUMBER_LENGTH) {
            throw new YamlProblem("number longer than " + MAX_NUMBER_LENGTH + " characters", s);
        }
        return F.numberNode(new BigDecimal(v.startsWith("+") ? v.substring(1) : v));
    }
}

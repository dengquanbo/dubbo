/*
 * Copyright 1999-2011 Alibaba Group.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.config;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.CollectionUtils;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.support.Parameter;

/**
 * 配置解析的工具方法、公共方法
 *
 * @author william.liangf
 * @export
 */
public abstract class AbstractConfig implements Serializable {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractConfig.class);
    private static final long serialVersionUID = 4267533505537413570L;

    // ========== 属性值的格式校验，参见本类的 #checkXXX 方法 BEGIN ==========

    private static final int MAX_LENGTH = 100;

    private static final int MAX_PATH_LENGTH = 200;

    private static final Pattern PATTERN_NAME = Pattern.compile("[\\-._0-9a-zA-Z]+");

    private static final Pattern PATTERN_MULTI_NAME = Pattern.compile("[,\\-._0-9a-zA-Z]+");

    private static final Pattern PATTERN_METHOD_NAME = Pattern.compile("[a-zA-Z][0-9a-zA-Z]*");

    private static final Pattern PATTERN_PATH = Pattern.compile("[/\\-$._0-9a-zA-Z]+");

    private static final Pattern PATTERN_NAME_HAS_SYMBOL = Pattern.compile("[:*,/\\-._0-9a-zA-Z]+");

    private static final Pattern PATTERN_KEY = Pattern.compile("[*,\\-._0-9a-zA-Z]+");

    /**
     * 新老版本的 properties 的 key 映射
     * <p>
     * key：新版本的配置 映射 value：旧版本的配置 映射
     */
    private static final Map<String, String> legacyProperties = new HashMap<String, String>();

    /**
     * 配置类名的后缀 例如，ServiceConfig 后缀为 Config；ServiceBean 后缀为 Bean。
     */
    private static final String[] SUFFIXS = new String[]{"Config", "Bean"};

    static {
        legacyProperties.put("dubbo.protocol.name", "dubbo.service.protocol");
        legacyProperties.put("dubbo.protocol.host", "dubbo.service.server.host");
        legacyProperties.put("dubbo.protocol.port", "dubbo.service.server.port");
        legacyProperties.put("dubbo.protocol.threads", "dubbo.service.max.thread.pool.size");
        legacyProperties.put("dubbo.consumer.timeout", "dubbo.service.invoke.timeout");
        legacyProperties.put("dubbo.consumer.retries", "dubbo.service.max.retry.providers");
        legacyProperties.put("dubbo.consumer.check", "dubbo.service.allow.no.provider");
        legacyProperties.put("dubbo.service.url", "dubbo.service.address");
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                if (logger.isInfoEnabled()) {
                    logger.info("Run shutdown hook now.");
                }
                ProtocolConfig.destroyAll();
            }
        }, "DubboShutdownHook"));
    }

    /**
     * 配置对象的编号
     */
    protected String id;


    /**
     * 将键对应的值转换成目标的值。
     * <p>
     * 因为，新老配置可能有一些差异，通过该方法进行转换。
     *
     * @param key   键
     * @param value 值
     * @return 转换后的值
     */
    private static String convertLegacyValue(String key, String value) {
        if (value != null && value.length() > 0) {
            if ("dubbo.service.max.retry.providers".equals(key)) {
                return String.valueOf(Integer.parseInt(value) - 1);
            } else if ("dubbo.service.allow.no.provider".equals(key)) {
                return String.valueOf(!Boolean.parseBoolean(value));
            }
        }
        return value;
    }

    /**
     * 读取环境变量和 properties 配置到配置对象
     * <p>
     * 参见：<a href= "https://dubbo.gitbooks.io/dubbo-user-book/configuration/properties.html">属性配置</a>
     *
     * @param config 配置对象
     */
    protected static void appendProperties(AbstractConfig config) {
        if (config == null) {
            return;
        }
        // 获得前缀
        // 如 <dubbo:application> 则为 dubbo.application.
        // 如 <dubbo:provider> 则为 dubbo.provider.
        String prefix = "dubbo." + getTagName(config.getClass()) + ".";
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();
                // 方法为获取基本类型，public T setXxx 方法
                if (name.length() > 3 && name.startsWith("set") && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 1 && isPrimitive(method.getParameterTypes()[0])) {

                    // 对应的属性名为 name
                    String property = StringUtils
                            .camelToSplitName(name.substring(3, 4).toLowerCase() + name.substring(4), "-");

                    String value = null;
                    // 如果配置了 id 属性，【环境变量】
                    // 优先从带有 `Config#id` 的配置中获取，例如：`dubbo.application.demo-provider.name` 。
                    if (config.getId() != null && config.getId().length() > 0) {
                        String pn = prefix + config.getId() + "." + property;
                        // 环境变量
                        value = System.getProperty(pn);
                        if (!StringUtils.isBlank(value)) {
                            logger.info("Use System Property " + pn + " to config dubbo");
                        }
                    }
                    // 【环境变量】获取不到，其次不带 `Config#id` 的配置中获取，例如：`dubbo.application.name`
                    if (value == null || value.length() == 0) {
                        String pn = prefix + property;
                        value = System.getProperty(pn);
                        if (!StringUtils.isBlank(value)) {
                            logger.info("Use System Property " + pn + " to config dubbo");
                        }
                    }
                    // 环境变量还是获取不到，覆盖优先级为：环境变量 > XML 配置 > properties 配置，因此需要使用 getter 判断 XML 是否已经设置
                    if (value == null || value.length() == 0) {
                        Method getter;
                        // 获得 get 方法
                        try {
                            getter = config.getClass().getMethod("get" + name.substring(3), new Class<?>[0]);
                        } catch (NoSuchMethodException e) {
                            try {
                                getter = config.getClass().getMethod("is" + name.substring(3), new Class<?>[0]);
                            } catch (NoSuchMethodException e2) {
                                getter = null;
                            }
                        }
                        if (getter != null) {
                            // 调用 get 方法，获取属性值，也就是判断 XML 是否已经设置
                            if (getter.invoke(config, new Object[0]) == null) {
                                // 如果 xml 未配置，【properties 配置】优先从带有 `Config#id`
                                // 的配置中获取，例如：`dubbo.application.demo-provider.name`
                                if (config.getId() != null && config.getId().length() > 0) {
                                    value = ConfigUtils.getProperty(prefix + config.getId() + "." + property);
                                }
                                // 【properties 配置】获取不到，其次不带 `Config#id` 的配置中获取，例如：`dubbo.application.name`
                                if (value == null || value.length() == 0) {
                                    value = ConfigUtils.getProperty(prefix + property);
                                }
                                // 【properties 配置】老版本兼容，获取不到，最后不带 `Config#id` 的配置中获取，例如：`dubbo.protocol.name`
                                if (value == null || value.length() == 0) {
                                    String legacyKey = legacyProperties.get(prefix + property);
                                    if (legacyKey != null && legacyKey.length() > 0) {
                                        value = convertLegacyValue(legacyKey, ConfigUtils.getProperty(legacyKey));
                                    }
                                }

                            }
                        }
                    }
                    // 获取到值，调用 set 方法，设置属性
                    if (value != null && value.length() > 0) {
                        method.invoke(config, new Object[]{convertPrimitive(method.getParameterTypes()[0], value)});
                    }
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    /**
     * 获取类名对应的属性标签，例如，ServiceConfig 对应为 service 。
     *
     * @param cls 类名
     * @return 标签
     */
    private static String getTagName(Class<?> cls) {
        String tag = cls.getSimpleName();
        for (String suffix : SUFFIXS) {
            // 类名是否以 config 或者 bean 结尾
            if (tag.endsWith(suffix)) {
                // 截取 config 或 bean 之前的名称
                tag = tag.substring(0, tag.length() - suffix.length());
                break;
            }
        }
        tag = tag.toLowerCase();
        return tag;
    }

    protected static void appendParameters(Map<String, String> parameters, Object config) {
        appendParameters(parameters, config, null);
    }

    /**
     * 静态方法，将配置对象的属性，添加到参数集合
     *
     * @param parameters 参数集合
     * @param config     配置对象
     * @param prefix     属性前缀
     */
    @SuppressWarnings("unchecked")
    protected static void appendParameters(Map<String, String> parameters, Object config, String prefix) {
        if (config == null) {
            return;
        }

        // 获得所有方法
        Method[] methods = config.getClass().getMethods();
        // 循环遍历方法
        for (Method method : methods) {
            try {
                // 方法名
                String name = method.getName();
                // 方法为获取基本类型，public void getXxx 和 public void isXxx 方法
                if ((name.startsWith("get") || name.startsWith("is")) && !"getClass".equals(name)
                        && Modifier.isPublic(method.getModifiers()) && method.getParameterTypes().length == 0
                        && isPrimitive(method.getReturnType())) {
                    // 获取 @Parameter 注解
                    Parameter parameter = method.getAnnotation(Parameter.class);

                    // 返回值为 Object 或者 @Parameter(excluded = true)，说明参数须排除
                    if (method.getReturnType() == Object.class || parameter != null && parameter.excluded()) {
                        continue;
                    }

                    // 获得属性名
                    int i = name.startsWith("get") ? 3 : 2;

                    // 如: getName() 处理后为 name，isDefault() 处理后为 default
                    String prop = StringUtils
                            .camelToSplitName(name.substring(i, i + 1).toLowerCase() + name.substring(i + 1), ".");

                    // 获得 @Parameter(key = "") 配置的 key，如果没有配置则用属性名作为 key
                    String key;
                    if (parameter != null && parameter.key() != null && parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        key = prop;
                    }
                    // 调用 get 方法，获得属性值
                    Object value = method.invoke(config, new Object[0]);
                    String str = String.valueOf(value).trim();
                    if (value != null && str.length() > 0) {
                        // 配置了转义
                        if (parameter != null && parameter.escaped()) {
                            str = URL.encode(str);
                        }
                        // 配置了拼接
                        if (parameter != null && parameter.append()) {
                            // 首先从 "default." 里获取，适用于 ServiceConfig =》ProviderConfig 、ReferenceConfig =》ConsumerConfig
                            String pre = (String) parameters.get(Constants.DEFAULT_KEY + "." + key);
                            if (pre != null && pre.length() > 0) {
                                str = pre + "," + str;
                            }
                            // 通过 `parameters` 属性配置，例如 `AbstractMethodConfig.parameters`
                            pre = (String) parameters.get(key);
                            if (pre != null && pre.length() > 0) {
                                str = pre + "," + str;
                            }
                        }
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }
                        parameters.put(key, str);
                    }
                    // 参数是必填的，但是 get 方法获取不到值，则需抛出异常
                    else if (parameter != null && parameter.required()) {
                        throw new IllegalStateException(config.getClass().getSimpleName() + "." + key + " == null");
                    }
                }
                // 方法是 public Map getParameters() 时
                else if ("getParameters".equals(name) && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0 && method.getReturnType() == Map.class) {
                    // 调用 getParameters() 方法，得到 Map 返回值
                    Map<String, String> map = (Map<String, String>) method.invoke(config, new Object[0]);

                    // 依次把 map 放入 parameters 中
                    if (map != null && map.size() > 0) {
                        String pre = (prefix != null && prefix.length() > 0 ? prefix + "." : "");
                        for (Map.Entry<String, String> entry : map.entrySet()) {
                            parameters.put(pre + entry.getKey().replace('-', '.'), entry.getValue());
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    protected static void appendAttributes(Map<Object, Object> parameters, Object config) {
        appendAttributes(parameters, config, null);
    }

    /**
     * 将带有 @Parameter(attribute = true) 配置对象的属性，添加到参数集合
     *
     * @param parameters 参数集合
     * @param config     配置对象
     * @param prefix     前缀
     */
    protected static void appendAttributes(Map<Object, Object> parameters, Object config, String prefix) {
        if (config == null) {
            return;
        }
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();
                // 方法为获取基本类型，public void getXxx 和 public void isXxx 方法
                if ((name.startsWith("get") || name.startsWith("is")) && !"getClass".equals(name)
                        && Modifier.isPublic(method.getModifiers()) && method.getParameterTypes().length == 0
                        && isPrimitive(method.getReturnType())) {
                    // 获取 @Parameter 注解
                    Parameter parameter = method.getAnnotation(Parameter.class);
                    // 没有配置注解或者@Parameter(attribute = false)跳过
                    if (parameter == null || !parameter.attribute())
                        continue;
                    // 获得属性名，先用@Parameter(key = "xxx")注解的配置的key，没有则使用属性名
                    String key;
                    if (parameter != null && parameter.key() != null && parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        int i = name.startsWith("get") ? 3 : 2;
                        key = name.substring(i, i + 1).toLowerCase() + name.substring(i + 1);
                    }
                    // 获得属性值，存在则添加到 parameters 集合
                    Object value = method.invoke(config, new Object[0]);
                    if (value != null) {
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }
                        parameters.put(key, value);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    /**
     * 类型是否为基本数据类型。
     *
     * @param type 类型
     * @return 是否
     */
    private static boolean isPrimitive(Class<?> type) {
        return type.isPrimitive() || type == String.class || type == Character.class || type == Boolean.class
                || type == Byte.class || type == Short.class || type == Integer.class || type == Long.class
                || type == Float.class || type == Double.class || type == Object.class;
    }

    /**
     * 将字符串转换成目标的基本数据类型的值
     *
     * @param type  目标的基本数据类型
     * @param value 字符串
     * @return 值
     */
    private static Object convertPrimitive(Class<?> type, String value) {
        if (type == char.class || type == Character.class) {
            return value.length() > 0 ? value.charAt(0) : '\0';
        } else if (type == boolean.class || type == Boolean.class) {
            return Boolean.valueOf(value);
        } else if (type == byte.class || type == Byte.class) {
            return Byte.valueOf(value);
        } else if (type == short.class || type == Short.class) {
            return Short.valueOf(value);
        } else if (type == int.class || type == Integer.class) {
            return Integer.valueOf(value);
        } else if (type == long.class || type == Long.class) {
            return Long.valueOf(value);
        } else if (type == float.class || type == Float.class) {
            return Float.valueOf(value);
        } else if (type == double.class || type == Double.class) {
            return Double.valueOf(value);
        }
        return value;
    }

    protected static void checkExtension(Class<?> type, String property, String value) {
        checkName(property, value);
        if (value != null && value.length() > 0 && !ExtensionLoader.getExtensionLoader(type).hasExtension(value)) {
            throw new IllegalStateException("No such extension " + value + " for " + property + "/" + type.getName());
        }
    }

    protected static void checkMultiExtension(Class<?> type, String property, String value) {
        checkMultiName(property, value);
        if (value != null && value.length() > 0) {
            String[] values = value.split("\\s*[,]+\\s*");
            for (String v : values) {
                if (v.startsWith(Constants.REMOVE_VALUE_PREFIX)) {
                    v = v.substring(1);
                }
                if (Constants.DEFAULT_KEY.equals(v)) {
                    continue;
                }
                if (!ExtensionLoader.getExtensionLoader(type).hasExtension(v)) {
                    throw new IllegalStateException(
                            "No such extension " + v + " for " + property + "/" + type.getName());
                }
            }
        }
    }

    protected static void checkLength(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, null);
    }

    protected static void checkPathLength(String property, String value) {
        checkProperty(property, value, MAX_PATH_LENGTH, null);
    }

    protected static void checkName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_NAME);
    }

    protected static void checkNameHasSymbol(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_NAME_HAS_SYMBOL);
    }

    protected static void checkKey(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_KEY);
    }

    protected static void checkMultiName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_MULTI_NAME);
    }

    protected static void checkPathName(String property, String value) {
        checkProperty(property, value, MAX_PATH_LENGTH, PATTERN_PATH);
    }

    protected static void checkMethodName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_METHOD_NAME);
    }

    protected static void checkParameterName(Map<String, String> parameters) {
        if (parameters == null || parameters.size() == 0) {
            return;
        }
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            // change by tony.chenl parameter value maybe has colon.for example napoli
            // address
            checkNameHasSymbol(entry.getKey(), entry.getValue());
        }
    }

    protected static void checkProperty(String property, String value, int maxlength, Pattern pattern) {
        if (value == null || value.length() == 0) {
            return;
        }
        if (value.length() > maxlength) {
            throw new IllegalStateException("Invalid " + property + "=\"" + value + "\" is longer than " + maxlength);
        }
        if (pattern != null) {
            Matcher matcher = pattern.matcher(value);
            if (!matcher.matches()) {
                throw new IllegalStateException("Invalid " + property + "=\"" + value
                        + "\" contain illegal charactor, only digit, letter, '-', '_' and '.' is legal.");
            }
        }
    }

    @Parameter(excluded = true)
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    protected void appendAnnotation(Class<?> annotationClass, Object annotation) {
        Method[] methods = annotationClass.getMethods();
        for (Method method : methods) {
            if (method.getDeclaringClass() != Object.class && method.getReturnType() != void.class
                    && method.getParameterTypes().length == 0 && Modifier.isPublic(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers())) {
                try {
                    String property = method.getName();
                    if ("interfaceClass".equals(property) || "interfaceName".equals(property)) {
                        property = "interface";
                    }
                    String setter = "set" + property.substring(0, 1).toUpperCase() + property.substring(1);
                    Object value = method.invoke(annotation, new Object[0]);
                    if (value != null && !value.equals(method.getDefaultValue())) {
                        Class<?> parameterType = ReflectUtils.getBoxedClass(method.getReturnType());
                        if ("filter".equals(property) || "listener".equals(property)) {
                            parameterType = String.class;
                            value = StringUtils.join((String[]) value, ",");
                        } else if ("parameters".equals(property)) {
                            parameterType = Map.class;
                            value = CollectionUtils.toStringMap((String[]) value);
                        }
                        try {
                            Method setterMethod = getClass().getMethod(setter, new Class<?>[]{parameterType});
                            setterMethod.invoke(this, new Object[]{value});
                        } catch (NoSuchMethodException e) {
                            // ignore
                        }
                    }
                } catch (Throwable e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public String toString() {
        try {
            StringBuilder buf = new StringBuilder();
            buf.append("<dubbo:");
            buf.append(getTagName(getClass()));
            Method[] methods = getClass().getMethods();
            for (Method method : methods) {
                try {
                    String name = method.getName();
                    if ((name.startsWith("get") || name.startsWith("is")) && !"getClass".equals(name)
                            && !"get".equals(name) && !"is".equals(name) && Modifier.isPublic(method.getModifiers())
                            && method.getParameterTypes().length == 0 && isPrimitive(method.getReturnType())) {
                        int i = name.startsWith("get") ? 3 : 2;
                        String key = name.substring(i, i + 1).toLowerCase() + name.substring(i + 1);
                        Object value = method.invoke(this, new Object[0]);
                        if (value != null) {
                            buf.append(" ");
                            buf.append(key);
                            buf.append("=\"");
                            buf.append(value);
                            buf.append("\"");
                        }
                    }
                } catch (Exception e) {
                    logger.warn(e.getMessage(), e);
                }
            }
            buf.append(" />");
            return buf.toString();
        } catch (Throwable t) { // 防御性容错
            logger.warn(t.getMessage(), t);
            return super.toString();
        }
    }

}
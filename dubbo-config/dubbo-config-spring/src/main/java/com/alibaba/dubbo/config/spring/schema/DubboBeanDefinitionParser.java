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
package com.alibaba.dubbo.config.spring.schema;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.*;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.config.spring.ServiceBean;
import com.alibaba.dubbo.rpc.Protocol;

/**
 * AbstractBeanDefinitionParser
 * 
 * @author william.liangf
 * @export
 */
public class DubboBeanDefinitionParser implements BeanDefinitionParser {

	private static final Logger logger = LoggerFactory.getLogger(DubboBeanDefinitionParser.class);
	private static final Pattern GROUP_AND_VERION = Pattern.compile("^[\\-.0-9_a-zA-Z]+(\\:[\\-.0-9_a-zA-Z]+)?$");
	/** 需要生成的bean */
	private final Class<?> beanClass;

	/** 是否需要生成ID */
	private final boolean required;

	public DubboBeanDefinitionParser(Class<?> beanClass, boolean required) {
		this.beanClass = beanClass;
		this.required = required;
	}

	@SuppressWarnings("unchecked")
	private static BeanDefinition parse(Element element, ParserContext parserContext, Class<?> beanClass,
			boolean required) {
		RootBeanDefinition beanDefinition = new RootBeanDefinition();
		// Spring 生成的对象为构造方法中传入的 class
		beanDefinition.setBeanClass(beanClass);
		// 默认为非懒加载，即只有被注入其他 bean，或者调用 getBean() 时才会初始化。
		beanDefinition.setLazyInit(false);
		// 获得 id 属性
		String id = element.getAttribute("id");
		// id 为空，required = true 时，进入下面的流程，说明需要 ID 属性
		if ((id == null || id.length() == 0) && required) {
			// 获得 bean 属性
			String generatedBeanName = element.getAttribute("name");
			if (generatedBeanName == null || generatedBeanName.length() == 0) {
				// beanClass 为 ProtocolConfig 时，名称默认为 dubbo
				if (ProtocolConfig.class.equals(beanClass)) {
					generatedBeanName = "dubbo";
				} else {
					// 其他的以 interface 作为名称
					generatedBeanName = element.getAttribute("interface");
				}
			}
			// 进入该流程，说明标签不是 <dubbo:protocol>，且从 id, name, interface 属性获取不到值
			// 则名称默认为类的名称
			if (generatedBeanName == null || generatedBeanName.length() == 0) {
				generatedBeanName = beanClass.getName();
			}
			id = generatedBeanName;
			int counter = 2;
			// 若 id 已存在，则自增
			while (parserContext.getRegistry().containsBeanDefinition(id)) {
				id = generatedBeanName + (counter++);
			}
		}
		// 当 id 不为空时，进入该流程，不进入的情况为获取 id 属性为空且 required = false
		if (id != null && id.length() > 0) {
			if (parserContext.getRegistry().containsBeanDefinition(id)) {
				throw new IllegalStateException("Duplicate spring bean id " + id);
			}
			// 添加到 Spring 的注册表
			parserContext.getRegistry().registerBeanDefinition(id, beanDefinition);
			// 设置 Bean 的 id 属性值
			beanDefinition.getPropertyValues().addPropertyValue("id", id);
		}
		// 当为 ProtocolConfig 时，进入该流程
		if (ProtocolConfig.class.equals(beanClass)) {
			for (String name : parserContext.getRegistry().getBeanDefinitionNames()) {
				BeanDefinition definition = parserContext.getRegistry().getBeanDefinition(name);

				// 已经在 Spring 的 BeanDefinition 是否含有 protocol 属性，为什么？
				// <dubbo:service protocol = ""/>
				// <dobbo:reference protocol = "" />
				// 都能配置 protocol 属性，所以需要判定优先级
				PropertyValue property = definition.getPropertyValues().getPropertyValue("protocol");
				if (property != null) {
					// 获得配置的协议
					Object value = property.getValue();
					// TODO
					if (value instanceof ProtocolConfig && id.equals(((ProtocolConfig) value).getName())) {
						definition.getPropertyValues().addPropertyValue("protocol", new RuntimeBeanReference(id));
					}
				}
			}
		}
		// 处理 <dubbo:service /> 标签
		else if (ServiceBean.class.equals(beanClass)) {
			// 获得 class 属性
			String className = element.getAttribute("class");
			// 处理 `class` 属性。
			// 例如 <dubbo:service id="sa" interface="com.alibaba.dubbo.demo.DemoService"
			// class="com.alibaba.dubbo.demo.provider.DemoServiceImpl" >

			if (className != null && className.length() > 0) {
				RootBeanDefinition classDefinition = new RootBeanDefinition();
				classDefinition.setBeanClass(ReflectUtils.forName(className));
				classDefinition.setLazyInit(false);
				// 解析 Service Bean 对象的属性
				parseProperties(element.getChildNodes(), classDefinition);

				// 设置 <dubbo:service ref="" /> 属性
				beanDefinition.getPropertyValues().addPropertyValue("ref",
						new BeanDefinitionHolder(classDefinition, id + "Impl"));
			}
		}
		// 解析 <dubbo:provider /> 的内嵌子元素 <dubbo:service />
		else if (ProviderConfig.class.equals(beanClass)) {
			parseNested(element, parserContext, ServiceBean.class, true, "service", "provider", id, beanDefinition);
		}
		// 解析 <dubbo:consumer /> 的内嵌子元素 <dubbo:reference />
		else if (ConsumerConfig.class.equals(beanClass)) {
			parseNested(element, parserContext, ReferenceBean.class, false, "reference", "consumer", id,
					beanDefinition);
		}
		Set<String> props = new HashSet<String>();
		ManagedMap parameters = null;
		// 遍历所有方法
		for (Method setter : beanClass.getMethods()) {
			String name = setter.getName();
			// 获得 set 方法
			if (name.length() > 3 && name.startsWith("set") && Modifier.isPublic(setter.getModifiers())
					&& setter.getParameterTypes().length == 1) {
				// 参数类型
				Class<?> type = setter.getParameterTypes()[0];
				// 参数名称
				String property = StringUtils.camelToSplitName(name.substring(3, 4).toLowerCase() + name.substring(4),
						"-");
				// 添加 props
				props.add(property);
				Method getter = null;
				try {
					// 获得对应的 get 方法
					getter = beanClass.getMethod("get" + name.substring(3), new Class<?>[0]);
				} catch (NoSuchMethodException e) {
					try {
						// 说明是 boolean 属性，获得 is 方法
						getter = beanClass.getMethod("is" + name.substring(3), new Class<?>[0]);
					} catch (NoSuchMethodException e2) {
					}
				}
				if (getter == null || !Modifier.isPublic(getter.getModifiers())
						|| !type.equals(getter.getReturnType())) {
					continue;
				}
				// 如果属性中包含 parameters，则试图解析子元素 <dubbo:parameter />
				if ("parameters".equals(property)) {
					parameters = parseParameters(element.getChildNodes(), beanDefinition);
				}
				// 如果属相包含 methods，则试图解析 <dubbo:method />
				else if ("methods".equals(property)) {
					parseMethods(id, element.getChildNodes(), beanDefinition, parserContext);
				}

				// 如果属性包含 arguments，则试图解析 <dubbo:arguments />
				else if ("arguments".equals(property)) {
					parseArguments(id, element.getChildNodes(), beanDefinition, parserContext);
				}
				// 不是官方的属性值
				else {
					// 读取值
					String value = element.getAttribute(property);
					if (value != null) {
						value = value.trim();
						if (value.length() > 0) {
							// 不想注册到注册中心的情况，即 `registry=N/A`
							if ("registry".equals(property) && RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(value)) {
								RegistryConfig registryConfig = new RegistryConfig();
								registryConfig.setAddress(RegistryConfig.NO_AVAILABLE);
								beanDefinition.getPropertyValues().addPropertyValue(property, registryConfig);
							}
							// ,分隔，则判断为多注册中心的情况，
							else if ("registry".equals(property) && value.indexOf(',') != -1) {
								parseMultiRef("registries", value, beanDefinition, parserContext);
							}
							// 多服务提供者的情况
							else if ("provider".equals(property) && value.indexOf(',') != -1) {
								parseMultiRef("providers", value, beanDefinition, parserContext);
							}
							// 多协议的情况
							else if ("protocol".equals(property) && value.indexOf(',') != -1) {
								parseMultiRef("protocols", value, beanDefinition, parserContext);
							} else {
								Object reference;
								// 处理属性类型为基本属性的情况
								if (isPrimitive(type)) {
									// 兼容性处理
									if ("async".equals(property) && "false".equals(value)
											|| "timeout".equals(property) && "0".equals(value)
											|| "delay".equals(property) && "0".equals(value)
											|| "version".equals(property) && "0.0.0".equals(value)
											|| "stat".equals(property) && "-1".equals(value)
											|| "reliable".equals(property) && "false".equals(value)) {
										// 兼容旧版本xsd中的default值
										value = null;
									}
									reference = value;
								}

								// 处理在 `<dubbo:provider />` 或者 `<dubbo:service />` 上定义了 `protocol` 属性的兼容性
								// 存在该注册协议的实现
								// Spring 注册表中不存在该 `<dubbo:provider />` 的定义或者 Spring 注册表中存在该编号，但是类型不为
								// ProtocolConfig
								else if ("protocol".equals(property)
										&& ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(value)
										&& (!parserContext.getRegistry().containsBeanDefinition(value)
												|| !ProtocolConfig.class.getName().equals(parserContext.getRegistry()
														.getBeanDefinition(value).getBeanClassName()))) {
									
									// 目前，`<dubbo:provider protocol="" />` 推荐独立成 `<dubbo:protocol />`
									if ("dubbo:provider".equals(element.getTagName())) {
										logger.warn("Recommended replace <dubbo:provider protocol=\"" + value
												+ "\" ... /> to <dubbo:protocol name=\"" + value + "\" ... />");
									}
									// 兼容旧版本配置
									ProtocolConfig protocol = new ProtocolConfig();
									protocol.setName(value);
									reference = protocol;
								} else if ("monitor".equals(property)
										&& (!parserContext.getRegistry().containsBeanDefinition(value)
												|| !MonitorConfig.class.getName().equals(parserContext.getRegistry()
														.getBeanDefinition(value).getBeanClassName()))) {
									// 兼容旧版本配置
									reference = convertMonitor(value);
								}
								// 处理 `onreturn` 属性
								else if ("onreturn".equals(property)) {
									// 按照 `.` 拆分
									int index = value.lastIndexOf(".");
									String returnRef = value.substring(0, index);
									String returnMethod = value.substring(index + 1);
									// 创建 RuntimeBeanReference ，指向回调的对象
									reference = new RuntimeBeanReference(returnRef);
									// 设置 `onreturnMethod` 到 BeanDefinition 的属性值
									beanDefinition.getPropertyValues().addPropertyValue("onreturnMethod", returnMethod);
								}
								// 处理 `onthrow` 属性
								else if ("onthrow".equals(property)) {
									// 按照 `.` 拆分
									int index = value.lastIndexOf(".");
									String throwRef = value.substring(0, index);
									String throwMethod = value.substring(index + 1);
									// 创建 RuntimeBeanReference ，指向回调的对象
									reference = new RuntimeBeanReference(throwRef);
									// 设置 `onthrowMethod` 到 BeanDefinition 的属性值
									beanDefinition.getPropertyValues().addPropertyValue("onthrowMethod", throwMethod);
								}
								// 通用解析
								else {
									// 指向的 Service 的 Bean 对象，必须是单例
									if ("ref".equals(property)
											&& parserContext.getRegistry().containsBeanDefinition(value)) {
										BeanDefinition refBean = parserContext.getRegistry().getBeanDefinition(value);
										if (!refBean.isSingleton()) {
											throw new IllegalStateException("The exported service ref " + value
													+ " must be singleton! Please set the " + value
													+ " bean scope to singleton, eg: <bean id=\"" + value
													+ "\" scope=\"singleton\" ...>");
										}
									}
									// 创建 RuntimeBeanReference ，指向 Service 的 Bean 对象
									reference = new RuntimeBeanReference(value);
								}
								// 设置 BeanDefinition 的属性值
								beanDefinition.getPropertyValues().addPropertyValue(property, reference);
							}
						}
					}
				}
			}
		}
		
		// 将 XML 元素，未在上面遍历到的属性，添加到 parameters 集合中。
		NamedNodeMap attributes = element.getAttributes();
		int len = attributes.getLength();
		for (int i = 0; i < len; i++) {
			Node node = attributes.item(i);
			String name = node.getLocalName();
			if (!props.contains(name)) {
				if (parameters == null) {
					parameters = new ManagedMap();
				}
				String value = node.getNodeValue();
				parameters.put(name, new TypedStringValue(value, String.class));
			}
		}
		if (parameters != null) {
			beanDefinition.getPropertyValues().addPropertyValue("parameters", parameters);
		}
		return beanDefinition;
	}

	protected static MonitorConfig convertMonitor(String monitor) {
		if (monitor == null || monitor.length() == 0) {
			return null;
		}
		if (GROUP_AND_VERION.matcher(monitor).matches()) {
			String group;
			String version;
			int i = monitor.indexOf(':');
			if (i > 0) {
				group = monitor.substring(0, i);
				version = monitor.substring(i + 1);
			} else {
				group = monitor;
				version = null;
			}
			MonitorConfig monitorConfig = new MonitorConfig();
			monitorConfig.setGroup(group);
			monitorConfig.setVersion(version);
			return monitorConfig;
		}
		return null;
	}

	/**
	 * 是否是基本类型(boolean、char、byte、short、int、long、float、double)以及对应的封装类
	 */
	private static boolean isPrimitive(Class<?> cls) {
		return cls.isPrimitive() || cls == Boolean.class || cls == Byte.class || cls == Character.class
				|| cls == Short.class || cls == Integer.class || cls == Long.class || cls == Float.class
				|| cls == Double.class || cls == String.class || cls == Date.class || cls == Class.class;
	}

	/**
	 * 解析多指向的情况，例如多注册中心，多协议等等。
	 *
	 * @param property
	 *            属性
	 * @param value
	 *            值
	 * @param beanDefinition
	 *            Bean 定义对象
	 * @param parserContext
	 *            Spring 解析上下文
	 */
	@SuppressWarnings("unchecked")
	private static void parseMultiRef(String property, String value, RootBeanDefinition beanDefinition,
			ParserContext parserContext) {
		String[] values = value.split("\\s*[,]+\\s*");
		ManagedList list = null;
		for (int i = 0; i < values.length; i++) {
			String v = values[i];
			if (v != null && v.length() > 0) {
				if (list == null) {
					list = new ManagedList();
				}
				list.add(new RuntimeBeanReference(v));
			}
		}
		beanDefinition.getPropertyValues().addPropertyValue(property, list);
	}
	/**
	 * 解析内嵌的指向的子 XML 元素
	 *
	 * @param element
	 *            父 XML 元素
	 * @param parserContext
	 *            Spring 解析上下文
	 * @param beanClass
	 *            内嵌解析子元素的 Bean 的类
	 * @param required
	 *            是否需要 Bean 的 `id` 属性
	 * @param tag
	 *            标签
	 * @param property
	 *            父 Bean 对象在子元素中的属性名
	 * @param ref
	 *            指向
	 * @param beanDefinition
	 *            父 Bean 定义对象
	 */
	private static void parseNested(Element element, ParserContext parserContext, Class<?> beanClass, boolean required,
			String tag, String property, String ref, BeanDefinition beanDefinition) {
		NodeList nodeList = element.getChildNodes();
		if (nodeList != null && nodeList.getLength() > 0) {
			boolean first = true;
			for (int i = 0; i < nodeList.getLength(); i++) {
				Node node = nodeList.item(i);
				if (node instanceof Element) {
					if (tag.equals(node.getNodeName()) || tag.equals(node.getLocalName())) {
						// 第一个 <dubbo:service/>
						if (first) {
							first = false;
							// default 官方描述为：是否为缺省协议，用于多协议
							// 如果读取 default 属性为空则，则设置 default = false
							String isDefault = element.getAttribute("default");
							if (isDefault == null || isDefault.length() == 0) {
								beanDefinition.getPropertyValues().addPropertyValue("default", "false");
							}
						}
						// 这里相当于从头开始 解析 <dubbo:service> 或者 <dubbo:reference>
						BeanDefinition subDefinition = parse((Element) node, parserContext, beanClass, required);
						// 设置子 BeanDefinition ，指向父 BeanDefinition
						if (subDefinition != null && ref != null && ref.length() > 0) {
							subDefinition.getPropertyValues().addPropertyValue(property, new RuntimeBeanReference(ref));
						}
					}
				}
			}
		}
	}

	/**
	 * 解析 <dubbo:service class="" /> 情况下，内涵的 `<property />` 的赋值。
	 *
	 * @param nodeList
	 *            子元素数组
	 * @param beanDefinition
	 *            Bean 定义对象
	 */
	private static void parseProperties(NodeList nodeList, RootBeanDefinition beanDefinition) {
		if (nodeList != null && nodeList.getLength() > 0) {
			// 遍历节点
			for (int i = 0; i < nodeList.getLength(); i++) {
				Node node = nodeList.item(i);
				if (node instanceof Element) {
					// 如果是 property 节点
					if ("property".equals(node.getNodeName()) || "property".equals(node.getLocalName())) {
						// 读取 name 属性的值
						String name = ((Element) node).getAttribute("name");
						if (name != null && name.length() > 0) {
							// 读取 value 属性的值
							String value = ((Element) node).getAttribute("value");
							// 读取 ref 属性的值
							String ref = ((Element) node).getAttribute("ref");
							// 设置到 BeanDefinition 的属性中，这里 name 优先于 ref
							if (value != null && value.length() > 0) {
								beanDefinition.getPropertyValues().addPropertyValue(name, value);
							} else if (ref != null && ref.length() > 0) {
								beanDefinition.getPropertyValues().addPropertyValue(name,
										new RuntimeBeanReference(ref));
							}
							// name 和 ref 都没设置这抛出异常
							else {
								throw new UnsupportedOperationException("Unsupported <property name=\"" + name
										+ "\"> sub tag, Only supported <property name=\"" + name
										+ "\" ref=\"...\" /> or <property name=\"" + name + "\" value=\"...\" />");
							}
						}
					}
				}
			}
		}
	}
	/**
	 * 解析 <dubbo:parameter />
	 *
	 * @param nodeList
	 *            子元素节点数组
	 * @param beanDefinition
	 *            Bean 定义对象
	 * @return 参数集合
	 */
	@SuppressWarnings("unchecked")
	private static ManagedMap parseParameters(NodeList nodeList, RootBeanDefinition beanDefinition) {
		if (nodeList != null && nodeList.getLength() > 0) {
			ManagedMap parameters = null;
			for (int i = 0; i < nodeList.getLength(); i++) {
				Node node = nodeList.item(i);
				if (node instanceof Element) {
					// 只解析子元素中的 <dubbo:parameter />
					if ("parameter".equals(node.getNodeName()) || "parameter".equals(node.getLocalName())) {
						if (parameters == null) {
							parameters = new ManagedMap();
						}
						String key = ((Element) node).getAttribute("key");
						String value = ((Element) node).getAttribute("value");
						// 这里如果设置了 hide = true 时，则 key 最后为 .key
						boolean hide = "true".equals(((Element) node).getAttribute("hide"));
						if (hide) {
							key = Constants.HIDE_KEY_PREFIX + key;
						}
						parameters.put(key, new TypedStringValue(value, String.class));
					}
				}
			}
			return parameters;
		}
		return null;
	}

	/**
	 * 解析 <dubbo:method />
	 *
	 * @param id
	 *            Bean 的 `id` 属性。
	 * @param nodeList
	 *            子元素节点数组
	 * @param beanDefinition
	 *            Bean 定义对象
	 * @param parserContext
	 *            解析上下文
	 */
	@SuppressWarnings("unchecked")
	private static void parseMethods(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
			ParserContext parserContext) {
		if (nodeList != null && nodeList.getLength() > 0) {
			ManagedList methods = null;
			for (int i = 0; i < nodeList.getLength(); i++) {
				Node node = nodeList.item(i);
				if (node instanceof Element) {
					Element element = (Element) node;
					// 只解析 <dubbo:method />
					if ("method".equals(node.getNodeName()) || "method".equals(node.getLocalName())) {
						// 读取 name 属性值
						String methodName = element.getAttribute("name");
						// 必须设置 name 属性
						if (methodName == null || methodName.length() == 0) {
							throw new IllegalStateException("<dubbo:method> name attribute == null");
						}
						if (methods == null) {
							methods = new ManagedList();
						}
						// 解析 <dubbo:method />，创建 BeanDefinition 对象
						BeanDefinition methodBeanDefinition = parse(((Element) node), parserContext, MethodConfig.class,
								false);
						// 添加到 `methods` 中
						String name = id + "." + methodName;
						BeanDefinitionHolder methodBeanDefinitionHolder = new BeanDefinitionHolder(methodBeanDefinition,
								name);
						methods.add(methodBeanDefinitionHolder);
					}
				}
			}
			// 添加到 BeanDefinition 中
			if (methods != null) {
				beanDefinition.getPropertyValues().addPropertyValue("methods", methods);
			}
		}
	}

	/**
	 * 解析 <dubbo:argument />
	 *
	 * @param id
	 *            Bean 的 `id` 属性。
	 * @param nodeList
	 *            子元素节点数组
	 * @param beanDefinition
	 *            Bean 定义对象
	 * @param parserContext
	 *            解析上下文
	 */
	@SuppressWarnings("unchecked")
	private static void parseArguments(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
			ParserContext parserContext) {
		if (nodeList != null && nodeList.getLength() > 0) {
			ManagedList arguments = null;
			for (int i = 0; i < nodeList.getLength(); i++) {
				Node node = nodeList.item(i);
				if (node instanceof Element) {
					Element element = (Element) node;
					// 只解析 <dubbo:argument />
					if ("argument".equals(node.getNodeName()) || "argument".equals(node.getLocalName())) {
						// 读取 index 属性的值
						String argumentIndex = element.getAttribute("index");
						if (arguments == null) {
							arguments = new ManagedList();
						}
						// 解析 <dubbo:argument />，创建 BeanDefinition 对象
						BeanDefinition argumentBeanDefinition = parse(((Element) node), parserContext,
								ArgumentConfig.class, false);
						String name = id + "." + argumentIndex;
						BeanDefinitionHolder argumentBeanDefinitionHolder = new BeanDefinitionHolder(
								argumentBeanDefinition, name);
						arguments.add(argumentBeanDefinitionHolder);
					}
				}
			}
			if (arguments != null) {
				beanDefinition.getPropertyValues().addPropertyValue("arguments", arguments);
			}
		}
	}

	public BeanDefinition parse(Element element, ParserContext parserContext) {
		return parse(element, parserContext, beanClass, required);
	}

}
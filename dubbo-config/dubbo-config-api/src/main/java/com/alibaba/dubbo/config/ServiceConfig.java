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

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.bytecode.Wrapper;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.dubbo.config.support.Parameter;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.cluster.ConfiguratorFactory;
import com.alibaba.dubbo.rpc.service.GenericService;

/**
 * ServiceConfig
 *
 * @author william.liangf
 * @export
 */
public class ServiceConfig<T> extends AbstractServiceConfig {

    private static final long serialVersionUID = 3033787999037024738L;

    /**
     * 全局静态属性
     * <p>
     * SPI，获取所有 Protocol 的实现类并获取 Adapter
     */
    private static final Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * 全局静态属性
     * <p>
     * SPI，获取所有 ProxyFactory 的实现类并获取 Adapter
     */
    private static final ProxyFactory proxyFactory =
            ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    // 接口类型
    private String interfaceName;

    private Class<?> interfaceClass;

    // 接口实现类引用
    private T ref;

    // 服务名称
    private String path;

    // 方法配置
    private List<MethodConfig> methods;

    private ProviderConfig provider;

    private final List<URL> urls = new ArrayList<URL>();

    /**
     * 服务配置暴露的 Exporter 。
     * <p>
     * URL ：Exporter 不一定是 1：1 的关系。
     * <p>
     * 例如 <li>{@link #scope} 未设置时，会暴露 Local + Remote 两个，也就是 URL ：Exporter = 1：2 </li>
     *
     * <li>{@link #scope} 设置为空时，不会暴露，也就是 URL ：Exporter = 1：0 </li>
     *
     * <li>{@link #scope} 设置为 Local 或 Remote 任一时，会暴露 Local 或 Remote 一个，也就是 URL ：Exporter = 1：1</li>
     * <p>
     * 非配置。
     *
     */
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();

    private transient volatile boolean exported;

    private transient volatile boolean unexported;

    private transient volatile boolean generic;

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        appendAnnotation(Service.class, service);
    }

    public URL toUrl() {
        return urls == null || urls.size() == 0 ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    @Parameter(excluded = true)
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    public boolean isUnexported() {
        return unexported;
    }

    public synchronized void export() {
        // 当 export 或者 delay 未配置，从 ProviderConfig 对象读取
        if (provider != null) {
            if (export == null) {
                export = provider.getExport();
            }
            if (delay == null) {
                delay = provider.getDelay();
            }
        }

        // 不暴露服务( <dubbo:provider export="false" /> ) ，则不进行暴露服务逻辑。
        if (export != null && !export.booleanValue()) {
            return;
        }

        // 延迟暴露，新建一个线程休眠 delay 时间后，开始暴露
        if (delay != null && delay > 0) {
            Thread thread = new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(delay);
                    } catch (Throwable e) {
                    }
                    doExport();
                }
            });
            thread.setDaemon(true);
            thread.setName("DelayExportServiceThread");
            thread.start();
        } else {
            doExport();
        }
    }

    protected synchronized void doExport() {
        // 已经被下线，抛出异常
        if (unexported) {
            throw new IllegalStateException("Already unexported!");
        }
        // 已被暴露，结束
        if (exported) {
            return;
        }
        // 标记已暴露
        exported = true;

        // 检查接口是否为空
        if (interfaceName == null || interfaceName.length() == 0) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }

        // 检测 provider 是否为空，为空则新建一个，并通过系统变量为其初始化
        checkDefault();

        // 下面几个 if 语句用于检测 provider、application 等核心配置类对象是否为空，
        // 若为空，则尝试从其他配置类对象中获取相应的实例。
        if (provider != null) {
            if (application == null) {
                // 从 provider 获取 application 配置
                application = provider.getApplication();
            }
            if (module == null) {
                // 从 provider 获取 module 配置
                module = provider.getModule();
            }
            if (registries == null) {
                // 从 provider 获取 registries 配置
                registries = provider.getRegistries();
            }
            if (monitor == null) {
                // 从 provider 获取 monitor 配置
                monitor = provider.getMonitor();
            }
            if (protocols == null) {
                // 从 provider 获取 protocol 配置
                protocols = provider.getProtocols();
            }
        }
        if (module != null) {
            if (registries == null) {
                // 从 ModuleConfig 获取 application 配置
                registries = module.getRegistries();
            }
            if (monitor == null) {
                // 从 ModuleConfig 获取 monitor 配置
                monitor = module.getMonitor();
            }
        }
        if (application != null) {
            if (registries == null) {
                // 从 ApplicationConfig 获取 registries 配置
                registries = application.getRegistries();
            }
            if (monitor == null) {
                // 从 ApplicationConfig 获取 application 配置
                monitor = application.getMonitor();
            }
        }
        // 泛化接口的实现
        if (ref instanceof GenericService) {
            interfaceClass = GenericService.class;
            // 标记为泛化接口
            generic = true;
        }
        // 普通接口的实现
        else {
            try {
                // 加载并初始化类
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 校验接口和方法
            checkInterfaceAndMethods(interfaceClass, methods);

            // 校验指向的 service 对象
            checkRef();

            // 标记为不是泛化接口
            generic = false;
        }

        // 处理服务接口客户端本地代理(local)相关。实际目前已经废弃，使用 stub 属性，参见 AbstractInterfaceConfig#setLocal 方法。
        if (local != null) {
            if (local == "true") {
                // 设为 true，表示使用缺省代理类名，即：接口名 + Local 后缀
                local = interfaceName + "Local";
            }
            Class<?> localClass;
            try {
                localClass = ClassHelper.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implemention class " + localClass.getName() + " not " +
                        "implement interface " + interfaceName);
            }
        }
        if (stub != null) {
            if (stub == "true") {
                // 设为 true，表示使用缺省代理类名，即：接口名 + Stub 后缀
                stub = interfaceName + "Stub";
            }
            Class<?> stubClass;
            try {
                stubClass = ClassHelper.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException("The stub implemention class " + stubClass.getName() + " not " +
                        "implement interface " + interfaceName);
            }
        }
        // 校验 ApplicationConfig 配置。
        checkApplication();

        // 校验 RegistryConfig 配置。
        checkRegistry();

        // 校验 ProtocolConfig 配置数组。
        checkProtocol();

        // 读取环境变量和 properties 配置到 ServiceConfig 对象
        appendProperties(this);

        // 校验 Stub 和 Mock 相关的配置
        checkStubAndMock(interfaceClass);

        // 服务路径，缺省为接口名
        if (path == null || path.length() == 0) {
            path = interfaceName;
        }
        doExportUrls();
    }

    private void checkRef() {
        // 检查引用不为空，并且引用必需实现接口
        if (ref == null) {
            throw new IllegalStateException("ref not allow null!");
        }
        // 检查是不是实现类
        if (!interfaceClass.isInstance(ref)) {
            throw new IllegalStateException("The class "
                    + ref.getClass().getName() + " unimplemented interface "
                    + interfaceClass + "!");
        }
    }

    public synchronized void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (exporters != null && exporters.size() > 0) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("unexpected err when unexport" + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;
    }

    /**
     * 暴露 Dubbo URL
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doExportUrls() {
        // 加载注册中心 URL 数组
        List<URL> registryURLs = loadRegistries(true);

        // 循环 protocols，向逐个注册中心分组暴露服务。
        for (ProtocolConfig protocolConfig : protocols) {
            doExportUrlsFor1Protocol(protocolConfig, registryURLs);
        }
    }

    /**
     * 基于单个协议，暴露服务
     *
     * @param protocolConfig 协议配置对象
     * @param registryURLs   注册中心链接对象数组
     */
    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        // 协议名
        String name = protocolConfig.getName();
        if (name == null || name.length() == 0) {
            name = "dubbo";
        }

        String host = protocolConfig.getHost();
        if (provider != null && (host == null || host.length() == 0)) {
            // 从 ProviderConfig 中获取 host
            host = provider.getHost();
        }
        boolean anyhost = false;
        if (NetUtils.isInvalidLocalHost(host)) {
            anyhost = true;
            try {
                host = InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException e) {
                logger.warn(e.getMessage(), e);
            }
            if (NetUtils.isInvalidLocalHost(host)) {
                if (registryURLs != null && registryURLs.size() > 0) {
                    for (URL registryURL : registryURLs) {
                        try {
                            Socket socket = new Socket();
                            try {
                                SocketAddress addr = new InetSocketAddress(registryURL.getHost(),
                                        registryURL.getPort());
                                socket.connect(addr, 1000);
                                host = socket.getLocalAddress().getHostAddress();
                                break;
                            } finally {
                                try {
                                    socket.close();
                                } catch (Throwable e) {
                                }
                            }
                        } catch (Exception e) {
                            logger.warn(e.getMessage(), e);
                        }
                    }
                }
                if (NetUtils.isInvalidLocalHost(host)) {
                    host = NetUtils.getLocalHost();
                }
            }
        }

        Integer port = protocolConfig.getPort();
        if (provider != null && (port == null || port == 0)) {
            port = provider.getPort();
        }
        final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
        if (port == null || port == 0) {
            port = defaultPort;
        }
        if (port == null || port <= 0) {
            port = getRandomPort(name);
            if (port == null || port < 0) {
                port = NetUtils.getAvailablePort(defaultPort);
                putRandomPort(name, port);
            }
            logger.warn("Use random available port(" + port + ") for protocol " + name);
        }

        // 将 `side`，`dubbo`，`timestamp`，`pid` 参数，添加到 `map` 集合中。
        Map<String, String> map = new HashMap<String, String>();
        if (anyhost) {
            map.put(Constants.ANYHOST_KEY, "true");
        }
        map.put(Constants.SIDE_KEY, Constants.PROVIDER_SIDE);
        // 设置版本
        map.put(Constants.DUBBO_VERSION_KEY, Version.getVersion());
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
        // 将各种配置对象，添加到 `map` 集合中。
        appendParameters(map, application);
        appendParameters(map, module);
        appendParameters(map, provider, Constants.DEFAULT_KEY);
        appendParameters(map, protocolConfig);
        appendParameters(map, this);
        // 将 MethodConfig 对象数组，添加到 `map` 集合中。
        if (methods != null && methods.size() > 0) {
            for (MethodConfig method : methods) {
                // 将 MethodConfig 对象，添加到 `map` 集合中。
                appendParameters(map, method, method.getName());
                // 当 配置了 `MethodConfig.retry = false` 时，强制禁用重试
                String retryKey = method.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        // 重试次数为 0
                        map.put(method.getName() + ".retries", "0");
                    }
                }

                // 将 ArgumentConfig 对象数组，添加到 `map` 集合中。如：
                /*
                 *
                 * <dubbo:method name="findXxx" timeout="3000" retries="2">
                 *     <dubbo:argument index="0" callback="true" />
                 * </dubbo:method>
                 *
                 * */
                List<ArgumentConfig> arguments = method.getArguments();
                if (arguments != null && arguments.size() > 0) {
                    for (ArgumentConfig argument : arguments) {
                        // 配置了 type 属性
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            Method[] methods = interfaceClass.getMethods();
                            //遍历所有方法
                            if (methods != null && methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    // 找到指定方法
                                    if (methodName.equals(method.getName())) {
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // 指定单个参数的位置 + 类型
                                        if (argument.getIndex() != -1) {
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                appendParameters(map, argument,
                                                        method.getName() + "." + argument.getIndex());
                                            } else {
                                                throw new IllegalArgumentException("argument config error : the index" +
                                                        " attribute and type attirbute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            //一个方法中多个callback
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException("argument config error : " +
                                                                "the index attribute and type attirbute not match " +
                                                                ":index :" + argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (argument.getIndex() != -1) { // 配置了 index 属性
                            appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else { // 两个都没配置，则报错
                            throw new IllegalArgumentException("argument config must set index or type attribute.eg: " +
                                    "<dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }

        // 泛化实现，添加 generic、methods
        if (generic) {
            map.put("generic", String.valueOf(true));
            map.put("methods", Constants.ANY_VALUE);
        } else {
            // 添加修订号
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put("revision", revision);
            }

            // 获得方法数组
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("NO method found in service interface " + interfaceClass.getName());
                map.put("methods", Constants.ANY_VALUE);
            } else {
                map.put("methods", StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }

        // token ，参见《令牌校验》http://dubbo.apache.org/zh-cn/docs/user/demos/token-authorization.html
        /*
         * <!--随机token令牌，使用UUID生成-->
         * <dubbo:provider interface="com.foo.BarService" token="true" />
         *
         * 或者
         *
         * <!--随机token令牌，使用UUID生成-->
         * <dubbo:service interface="com.foo.BarService" token="true" />
         *
         * */
        if (!ConfigUtils.isEmpty(token)) {
            if (ConfigUtils.isDefault(token)) {
                map.put("token", UUID.randomUUID().toString());
            } else {
                map.put("token", token);
            }
        }

        // 协议为 injvm 时，不注册，不通知。
        if ("injvm".equals(protocolConfig.getName())) {
            // 不注册
            protocolConfig.setRegister(false);
            map.put("notify", "false");
        }
        // 导出服务
        String contextPath = protocolConfig.getContextpath();
        if ((contextPath == null || contextPath.length() == 0) && provider != null) {
            contextPath = provider.getContextpath();
        }

        // 创建 Dubbo URL 对象
        URL url = new URL(name, host, port, (contextPath == null || contextPath.length() == 0 ? "" : contextPath +
                "/") + path, map);

        // 配置规则，参见《配置规则》http://dubbo.apache.org/zh-cn/docs/user/demos/config-rule.html
        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        // 获得 scope 属性
        String scope = url.getParameter(Constants.SCOPE_KEY);

        // 配置为 scope = none 不暴露
        if (!Constants.SCOPE_NONE.toString().equalsIgnoreCase(scope)) {

            // 配置不是 remote 的情况下做本地暴露 (配置为remote，则表示只暴露远程服务)
            if (!Constants.SCOPE_REMOTE.toString().equalsIgnoreCase(scope)) {
                exportLocal(url);
            }
            // 如果配置不是local则暴露为远程服务.(配置为local，则表示只暴露远程服务)
            if (!Constants.SCOPE_LOCAL.toString().equalsIgnoreCase(scope)) {
                if (logger.isInfoEnabled()) {
                    logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                }
                if (registryURLs != null && registryURLs.size() > 0
                        && url.getParameter("register", true)) {
                    for (URL registryURL : registryURLs) {
                        // "dynamic" ：服务是否动态注册，如果设为false，注册后将显示后disable状态，需人工启用，并且服务提供者停止时，也不会自动取消册，需人工禁用。
                        url = url.addParameterIfAbsent("dynamic", registryURL.getParameter("dynamic"));

                        // 获得监控中心 URL
                        URL monitorUrl = loadMonitor(registryURL);
                        if (monitorUrl != null) {
                            url = url.addParameterAndEncoded(Constants.MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to " +
                                    "registry " + registryURL);
                        }
                        // 使用 ProxyFactory 创建 Invoker 对象
                        Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass,
                                registryURL.addParameterAndEncoded(Constants.EXPORT_KEY, url.toFullString()));

                        // 使用 Protocol 暴露 Invoker 对象
                        Exporter<?> exporter = protocol.export(invoker);

                        // 添加到 exporters
                        exporters.add(exporter);
                    }
                } else { // 用于被服务消费者直连服务提供者，参见文档 http://dubbo.apache.org/zh-cn/docs/user/demos/explicit-target.html 。主要用于开发测试环境使用。
                    // 使用 ProxyFactory 创建 Invoker 对象
                    Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);

                    // 使用 Protocol 暴露 Invoker 对象
                    Exporter<?> exporter = protocol.export(invoker);
                    // 添加到 exporters
                    exporters.add(exporter);
                }
            }
        }
        this.urls.add(url);
    }

    /**
     * 本地暴露服务
     *
     * @param url 注册中心 URL
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void exportLocal(URL url) {
        // 协议必须为 injvm
        if (!Constants.LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
            // 创建本地 Dubbo URL
            URL local = URL.valueOf(url.toFullString())
                    .setProtocol(Constants.LOCAL_PROTOCOL) // injvm
                    .setHost(NetUtils.LOCALHOST) // 本地
                    .setPort(0); // 端口=0
            // 使用 ProxyFactory 创建 Invoker 对象
            // 使用 Protocol 暴露 Invoker 对象
            Exporter<?> exporter = protocol.export(
                    proxyFactory.getInvoker(ref, (Class) interfaceClass, local));

            // 添加到 exporters
            exporters.add(exporter);
            logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry");
        }
    }

    private void checkDefault() {
        // 如果未配置 provider，则默认使用 ProviderConfig 的默认属性值
        if (provider == null) {
            provider = new ProviderConfig();
        }
        // 为 ProviderConfig 设置属性
        appendProperties(provider);
    }

    private void checkProtocol() {
        if ((protocols == null || protocols.size() == 0)
                && provider != null) {
            setProtocols(provider.getProtocols());
        }
        // 兼容旧版本
        if (protocols == null || protocols.size() == 0) {
            setProtocol(new ProtocolConfig());
        }
        for (ProtocolConfig protocolConfig : protocols) {
            if (StringUtils.isEmpty(protocolConfig.getName())) {
                protocolConfig.setName("dubbo");
            }
            appendProperties(protocolConfig);
        }
    }

    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (ref instanceof GenericService) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            }
        } catch (ClassNotFoundException t) {
            throw new IllegalStateException(t.getMessage(), t);
        }
        return interfaceClass;
    }

    /**
     * @param interfaceClass
     * @see #setInterface(Class)
     * @deprecated
     */
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (id == null || id.length() == 0) {
            id = interfaceName;
        }
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? (String) null : interfaceClass.getName());
    }

    public T getRef() {
        return ref;
    }

    public void setRef(T ref) {
        this.ref = ref;
    }

    @Parameter(excluded = true)
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        checkPathName("path", path);
        this.path = path;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ProviderConfig getProvider() {
        return provider;
    }

    public void setProvider(ProviderConfig provider) {
        this.provider = provider;
    }

    public List<URL> getExportedUrls() {
        return urls;
    }

    // ======== Deprecated ========

    /**
     * @deprecated Replace to getProtocols()
     */
    @Deprecated
    public List<ProviderConfig> getProviders() {
        return convertProtocolToProvider(protocols);
    }

    /**
     * @deprecated Replace to setProtocols()
     */
    @Deprecated
    public void setProviders(List<ProviderConfig> providers) {
        this.protocols = convertProviderToProtocol(providers);
    }

    @Deprecated
    private static final List<ProtocolConfig> convertProviderToProtocol(List<ProviderConfig> providers) {
        if (providers == null || providers.size() == 0) {
            return null;
        }
        List<ProtocolConfig> protocols = new ArrayList<ProtocolConfig>(providers.size());
        for (ProviderConfig provider : providers) {
            protocols.add(convertProviderToProtocol(provider));
        }
        return protocols;
    }

    @Deprecated
    private static final List<ProviderConfig> convertProtocolToProvider(List<ProtocolConfig> protocols) {
        if (protocols == null || protocols.size() == 0) {
            return null;
        }
        List<ProviderConfig> providers = new ArrayList<ProviderConfig>(protocols.size());
        for (ProtocolConfig provider : protocols) {
            providers.add(convertProtocolToProvider(provider));
        }
        return providers;
    }

    @Deprecated
    private static final ProtocolConfig convertProviderToProtocol(ProviderConfig provider) {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName(provider.getProtocol().getName());
        protocol.setServer(provider.getServer());
        protocol.setClient(provider.getClient());
        protocol.setCodec(provider.getCodec());
        protocol.setHost(provider.getHost());
        protocol.setPort(provider.getPort());
        protocol.setPath(provider.getPath());
        protocol.setPayload(provider.getPayload());
        protocol.setThreads(provider.getThreads());
        protocol.setParameters(provider.getParameters());
        return protocol;
    }

    @Deprecated
    private static final ProviderConfig convertProtocolToProvider(ProtocolConfig protocol) {
        ProviderConfig provider = new ProviderConfig();
        provider.setProtocol(protocol);
        provider.setServer(protocol.getServer());
        provider.setClient(protocol.getClient());
        provider.setCodec(protocol.getCodec());
        provider.setHost(protocol.getHost());
        provider.setPort(protocol.getPort());
        provider.setPath(protocol.getPath());
        provider.setPayload(protocol.getPayload());
        provider.setThreads(protocol.getThreads());
        provider.setParameters(protocol.getParameters());
        return provider;
    }

    private static Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        if (RANDOM_PORT_MAP.containsKey(protocol)) {
            return RANDOM_PORT_MAP.get(protocol);
        }
        return Integer.MIN_VALUE;
    }

    private static void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
        }
    }
}
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
package com.alibaba.dubbo.rpc.protocol.injvm;

import java.util.Map;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.protocol.AbstractProtocol;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;

/**
 * InjvmProtocol
 *
 * @author qian.lei
 * @author william.liangf
 */
public class InjvmProtocol extends AbstractProtocol implements Protocol {

    public static final String NAME = Constants.LOCAL_PROTOCOL;
    /**
     * 默认端口
     */
    public static final int DEFAULT_PORT = 0;

    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    /**
     * 单例。在 Dubbo SPI 中，被初始化，有且仅有一次。
     */
    private static InjvmProtocol INSTANCE;

    public InjvmProtocol() {
        INSTANCE = this;
    }

    public static InjvmProtocol getInjvmProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(InjvmProtocol.NAME); // load
        }
        return INSTANCE;
    }

    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        return new InjvmExporter<T>(invoker, invoker.getUrl().getServiceKey(), exporterMap);
    }

    public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
        return new InjvmInvoker<T>(serviceType, url, url.getServiceKey(), exporterMap);
    }

    static Exporter<?> getExporter(Map<String, Exporter<?>> map, URL key) {
        Exporter<?> result = null;

        if (!key.getServiceKey().contains("*")) {
            result = map.get(key.getServiceKey());
        } else {
            if (map != null && !map.isEmpty()) {
                for (Exporter<?> exporter : map.values()) {
                    if (UrlUtils.isServiceKeyMatch(key, exporter.getInvoker().getUrl())) {
                        result = exporter;
                        break;
                    }
                }
            }
        }

        if (result == null) {
            return null;
        } else if (ProtocolUtils.isGeneric(
                result.getInvoker().getUrl().getParameter(Constants.GENERIC_KEY))) {
            return null;
        } else {
            return result;
        }
    }

    /**
     * 是否本地引用
     *
     * @param url URL
     * @return 是否
     */
    public boolean isInjvmRefer(URL url) {
        /*
         * 两种方式配置本地引用
         *
         * 推荐 <dubbo:service scope="local" />
         * <p>
         * 不推荐使用，准备废弃 <dubbo:service injvm="true" />
         *
         */
        final boolean isJvmRefer;
        // 从 url 中获取 'scope' 属性
        String scope = url.getParameter(Constants.SCOPE_KEY);
        // 当 `protocol = injvm` 时，本身已经是jvm协议了，走正常流程就是了.
        if (Constants.LOCAL_PROTOCOL.toString().equals(url.getProtocol())) {
            isJvmRefer = false;
        }
        // scope=local || injvm=true 等价 injvm标签未来废弃掉，声明为本地引用
        else if (Constants.SCOPE_LOCAL.equals(scope) || (url.getParameter("injvm", false))) {
            isJvmRefer = true;
        }
        // 当 `scope = remote` 时，远程引用
        else if (Constants.SCOPE_REMOTE.equals(scope)) {
            isJvmRefer = false;
        }
        // 当 `generic = true` 时，即使用泛化调用，远程引用。
        else if (url.getParameter(Constants.GENERIC_KEY, false)) {
            //泛化调用不走本地
            isJvmRefer = false;
        }
        // 当本地已经有该 Exporter 时，本地引用
        else if (getExporter(exporterMap, url) != null) {
            isJvmRefer = true;
        }
        // 默认，远程引用
        else {
            isJvmRefer = false;
        }
        return isJvmRefer;
    }
}
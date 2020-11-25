/*
 * Copyright 1999-2012 Alibaba Group.
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
package com.alibaba.dubbo.common.extension.factory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.ExtensionFactory;
import com.alibaba.dubbo.common.extension.ExtensionLoader;

/**
 * 自适应 ExtensionFactory 拓展实现类
 *
 * @author william.liangf
 */
@Adaptive
public class AdaptiveExtensionFactory implements ExtensionFactory {

    /**
     * ExtensionFactory 拓展对象集合
     */
    private final List<ExtensionFactory> factories;

    public AdaptiveExtensionFactory() {
        // 使用 ExtensionLoader 加载拓展对象实现类。
        ExtensionLoader<ExtensionFactory> loader = ExtensionLoader.getExtensionLoader(ExtensionFactory.class);
        List<ExtensionFactory> list = new ArrayList<ExtensionFactory>();
        // 遍历所有名称
        for (String name : loader.getSupportedExtensions()) {
            // 添加实现类
            list.add(loader.getExtension(name));
        }
        // 不可变的 list
        factories = Collections.unmodifiableList(list);
    }

    public <T> T getExtension(Class<T> type, String name) {
        // 遍历工厂数组，直到获得到属性
        for (ExtensionFactory factory : factories) {
            T extension = factory.getExtension(type, name);
            if (extension != null) {
                return extension;
            }
        }
        return null;
    }

}

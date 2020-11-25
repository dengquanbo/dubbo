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

import org.springframework.beans.factory.xml.NamespaceHandlerSupport;

import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ConsumerConfig;
import com.alibaba.dubbo.config.ModuleConfig;
import com.alibaba.dubbo.config.MonitorConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.ProviderConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.spring.AnnotationBean;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.config.spring.ServiceBean;

/**
 * DubboNamespaceHandler
 * 
 * @author william.liangf
 * @export
 */
public class DubboNamespaceHandler extends NamespaceHandlerSupport {

	static {
		Version.checkDuplicate(DubboNamespaceHandler.class);
	}

	public void init() {
		// 用于解析 <dubbo:application> 标签
	    registerBeanDefinitionParser("application", new DubboBeanDefinitionParser(ApplicationConfig.class, true));
		// 用于解析 <dubbo:module> 标签
	    registerBeanDefinitionParser("module", new DubboBeanDefinitionParser(ModuleConfig.class, true));
		// 用于解析 <dubbo:registry> 标签
	    registerBeanDefinitionParser("registry", new DubboBeanDefinitionParser(RegistryConfig.class, true));
		// 用于解析 <dubbo:monitor> 标签
	    registerBeanDefinitionParser("monitor", new DubboBeanDefinitionParser(MonitorConfig.class, true));
		// 用于解析 <dubbo:provider> 标签
	    registerBeanDefinitionParser("provider", new DubboBeanDefinitionParser(ProviderConfig.class, true));
		// 用于解析 <dubbo:consumer> 标签
        registerBeanDefinitionParser("consumer", new DubboBeanDefinitionParser(ConsumerConfig.class, true));
		// 用于解析 <dubbo:protocol> 标签
        registerBeanDefinitionParser("protocol", new DubboBeanDefinitionParser(ProtocolConfig.class, true));
		// 用于解析 <dubbo:service> 标签
        registerBeanDefinitionParser("service", new DubboBeanDefinitionParser(ServiceBean.class, true));
		// 用于解析 <dubbo:reference> 标签
        registerBeanDefinitionParser("reference", new DubboBeanDefinitionParser(ReferenceBean.class, false));
		// 用于解析 <dubbo:annotation> 标签
        registerBeanDefinitionParser("annotation", new DubboBeanDefinitionParser(AnnotationBean.class, true));
    }

}
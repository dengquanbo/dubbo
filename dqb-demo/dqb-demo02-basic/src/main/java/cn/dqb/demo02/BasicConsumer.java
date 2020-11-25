package cn.dqb.demo02;

import org.springframework.context.support.ClassPathXmlApplicationContext;

import cn.dqb.demo01.DemoService;

public class BasicConsumer {

    public static void main(String[] args) {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("spring/dubbo-demo-consumer.xml");
        context.start();
        DemoService demoService = (DemoService) context.getBean("demoService");
        String hello = demoService.sayHello("world");
        System.out.println(hello);

//        System.out.println("start void test...");
//        demoService.testVoid();
    }
}
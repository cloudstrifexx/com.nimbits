/*
 * Copyright 2016 Benjamin Sautner
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.nimbits.server;


import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.logging.Logger;


public class ApplicationListener implements ServletContextListener {

    private static final Logger log = Logger.getLogger(ApplicationListener.class.getName());

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {

        log.info("nimbits s GAE context Initialized");


    }


    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {
        log.info("contextDestroyed");
    }
}

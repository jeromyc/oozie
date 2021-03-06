/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.oozie.servlet;

import org.apache.oozie.service.AuthorizationException;
import org.apache.commons.logging.Log;
import org.apache.hadoop.conf.Configuration;
import org.apache.oozie.client.rest.JsonBean;
import org.apache.oozie.client.rest.JsonWorkflowJob;
import org.apache.oozie.client.rest.RestConstants;
import org.apache.oozie.client.OozieClient;
import org.apache.oozie.util.XConfiguration;
import org.apache.oozie.util.XLog;
import org.apache.oozie.BaseEngineException;
import org.apache.oozie.DagEngine;
import org.apache.oozie.DagEngineException;
import org.apache.oozie.ErrorCode;
import org.apache.oozie.service.DagEngineService;
import org.apache.oozie.service.AuthorizationService;
import org.apache.oozie.service.Services;
import org.apache.oozie.service.XLogService;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;

public abstract class BaseJobServlet extends JsonRestServlet {

    private static final ResourceInfo RESOURCES_INFO[] = new ResourceInfo[1];

    static {
        RESOURCES_INFO[0] = new ResourceInfo("*", Arrays.asList("PUT", "GET"), Arrays.asList(new ParameterInfo(
                RestConstants.ACTION_PARAM, String.class, true, Arrays.asList("PUT")), new ParameterInfo(
                RestConstants.JOB_SHOW_PARAM, String.class, false, Arrays.asList("GET"))));
    }

    public BaseJobServlet(String instrumentationName) {
        super(instrumentationName, RESOURCES_INFO);
    }

    /**
     * Perform various job related actions - start, suspend, resume, kill, etc.
     */
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String jobId = getResourceName(request);
        request.setAttribute(AUDIT_PARAM, jobId);
        request.setAttribute(AUDIT_OPERATION, request.getParameter(RestConstants.ACTION_PARAM));
        try {
            AuthorizationService auth = Services.get().get(AuthorizationService.class);
            auth.authorizeForJob(getUser(request), jobId, true);
        }
        catch (AuthorizationException ex) {
            throw new XServletException(HttpServletResponse.SC_UNAUTHORIZED, ex);
        }

        String action = request.getParameter(RestConstants.ACTION_PARAM);
        if (action.equals(RestConstants.JOB_ACTION_START)) {
            stopCron();
            startJob(request, response);
            startCron();
            response.setStatus(HttpServletResponse.SC_OK);
        }
        else {
            if (action.equals(RestConstants.JOB_ACTION_RESUME)) {
                stopCron();
                resumeJob(request, response);
                startCron();
                response.setStatus(HttpServletResponse.SC_OK);
            }
            else {
                if (action.equals(RestConstants.JOB_ACTION_SUSPEND)) {
                    stopCron();
                    suspendJob(request, response);
                    startCron();
                    response.setStatus(HttpServletResponse.SC_OK);
                }
                else {
                    if (action.equals(RestConstants.JOB_ACTION_KILL)) {
                        stopCron();
                        killJob(request, response);
                        startCron();
                        response.setStatus(HttpServletResponse.SC_OK);
                    }
                    else {
                        if (action.equals(RestConstants.JOB_ACTION_RERUN)) {
                            validateContentType(request, RestConstants.XML_CONTENT_TYPE);
                            Configuration conf = new XConfiguration(request.getInputStream());

                            stopCron();

                            checkAuthorizationForApp(getUser(request), conf);

                            reRunJob(request, response, conf);
                            startCron();
                            response.setStatus(HttpServletResponse.SC_OK);
                        }
                        else {
                            throw new XServletException(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.E0303,
                                                        RestConstants.ACTION_PARAM, action);
                        }
                    }
                }
            }
        }
    }

    /**
     * Validate the configuration user/group. <p/>
     *
     * @param requestUser user in request.
     * @param conf configuration.
     * @throws XServletException thrown if the configuration does not have a property {@link
     * org.apache.oozie.client.OozieClient#USER_NAME}.
     */
    static void checkAuthorizationForApp(String requestUser, Configuration conf) throws XServletException {
        String user = conf.get(OozieClient.USER_NAME);
        String group = conf.get(OozieClient.GROUP_NAME);
        try {
            if (user == null) {
                throw new XServletException(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.E0401, OozieClient.USER_NAME);
            }
            if (!requestUser.equals(UNDEF) && !user.equals(requestUser)) {
                throw new XServletException(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.E0400, requestUser, user);
            }
            AuthorizationService auth = Services.get().get(AuthorizationService.class);
            if (group == null) {
                group = auth.getDefaultGroup(user);
                conf.set(OozieClient.GROUP_NAME, group);
            }
            else {
                auth.authorizeForGroup(user, group);
            }
            XLog.Info.get().setParameter(XLogService.GROUP, group);
            String wfPath = conf.get(OozieClient.APP_PATH);
            String coordPath = conf.get(OozieClient.COORDINATOR_APP_PATH);
            ServletUtilities.ValidateAppPath(wfPath, coordPath);

            if (wfPath != null) {
                auth.authorizeForApp(user, group, wfPath, "workflow.xml", conf);
            }
            else {
                auth.authorizeForApp(user, group, coordPath, "coordinator.xml", conf);
            }
        }
        catch (AuthorizationException ex) {
            XLog.getLog(BaseJobServlet.class).info("AuthorizationException ", ex);
            throw new XServletException(HttpServletResponse.SC_UNAUTHORIZED, ex);
        }
    }

    /**
     * Return information about jobs.
     */
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String jobId = getResourceName(request);
        String show = request.getParameter(RestConstants.JOB_SHOW_PARAM);

        try {
            AuthorizationService auth = Services.get().get(AuthorizationService.class);
            auth.authorizeForJob(getUser(request), jobId, false);
        }
        catch (AuthorizationException ex) {
            throw new XServletException(HttpServletResponse.SC_UNAUTHORIZED, ex);
        }

        if (show == null || show.equals(RestConstants.JOB_SHOW_INFO)) {
            stopCron();
            JsonBean job = null;
            try {
                job = getJob(request, response);
            }
            catch (BaseEngineException e) {
                // TODO Auto-generated catch block
                // e.printStackTrace();

                throw new XServletException(HttpServletResponse.SC_BAD_REQUEST, e);
            }
            startCron();
            sendJsonResponse(response, HttpServletResponse.SC_OK, job);
        }
        else {
            if (show.equals(RestConstants.JOB_SHOW_LOG)) {
                response.setContentType(TEXT_UTF8);
                streamJobLog(request, response);
            }
            else {
                if (show.equals(RestConstants.JOB_SHOW_DEFINITION)) {
                    stopCron();
                    response.setContentType(XML_UTF8);
                    String wfDefinition = getJobDefinition(request, response);
                    startCron();
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().write(wfDefinition);
                }
                else {
                    throw new XServletException(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.E0303,
                                                RestConstants.JOB_SHOW_PARAM, show);
                }
            }
        }
    }

    /**
     * abstract method to start a job, either workflow or coordinator
     *
     * @param request
     * @param response
     * @throws XServletException
     * @throws IOException TODO
     */
    abstract void startJob(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

    /**
     * abstract method to resume a job, either workflow or coordinator
     *
     * @param request
     * @param response
     * @throws XServletException
     * @throws IOException TODO
     */
    abstract void resumeJob(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

    /**
     * abstract method to suspend a job, either workflow or coordinator
     *
     * @param request
     * @param response
     * @throws XServletException
     * @throws IOException TODO
     */
    abstract void suspendJob(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

    /**
     * abstract method to kill a job, either workflow or coordinator
     *
     * @param request
     * @param response
     * @throws XServletException
     * @throws IOException TODO
     */
    abstract void killJob(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

    /**
     * abstract method to re-run a job, either workflow or coordinator
     *
     * @param request
     * @param response
     * @param conf
     * @throws XServletException
     * @throws IOException TODO
     */
    abstract void reRunJob(HttpServletRequest request, HttpServletResponse response, Configuration conf)
            throws XServletException, IOException;

    /**
     * abstract method to get a job, either workflow or coordinator, in JsonBean representation
     *
     * @param request
     * @param response
     * @return JsonBean representation of a job, either workflow or coordinator
     * @throws XServletException
     * @throws IOException TODO
     * @throws BaseEngineException
     */
    abstract JsonBean getJob(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException, BaseEngineException;

    /**
     * abstract method to get definition of a job, either workflow or coordinator
     *
     * @param request
     * @param response
     * @return job, either workflow or coordinator, definition in string format
     * @throws XServletException
     * @throws IOException TODO
     */
    abstract String getJobDefinition(HttpServletRequest request, HttpServletResponse response)
            throws XServletException, IOException;

    /**
     * abstract method to get and stream log information of job, either workflow or coordinator
     *
     * @param request
     * @param response
     * @throws XServletException
     * @throws IOException
     */
    abstract void streamJobLog(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

}

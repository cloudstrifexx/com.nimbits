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

package com.nimbits.server.api.v3;

import com.google.common.base.Optional;
import com.google.common.collect.Range;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.nimbits.client.enums.EntityType;
import com.nimbits.client.enums.point.PointType;
import com.nimbits.client.exception.ValueException;
import com.nimbits.client.model.common.impl.CommonFactory;
import com.nimbits.client.model.entity.Entity;
import com.nimbits.client.model.hal.*;
import com.nimbits.client.model.point.Point;
import com.nimbits.client.model.user.Credentials;
import com.nimbits.client.model.user.User;
import com.nimbits.client.model.user.UserModel;
import com.nimbits.client.model.user.UserSource;
import com.nimbits.client.model.value.Value;
import com.nimbits.server.data.DataProcessor;
import com.nimbits.server.geo.GeoSpatialDao;
import com.nimbits.server.gson.GsonFactory;
import com.nimbits.server.process.BlobStore;
import com.nimbits.server.process.task.TaskService;
import com.nimbits.server.process.task.ValueTask;
import com.nimbits.server.transaction.calculation.CalculationService;
import com.nimbits.server.transaction.entity.dao.EntityDao;
import com.nimbits.server.transaction.entity.service.EntityService;
import com.nimbits.server.transaction.subscription.SubscriptionService;
import com.nimbits.server.transaction.summary.SummaryService;
import com.nimbits.server.transaction.sync.SyncService;
import com.nimbits.server.transaction.user.dao.UserDao;
import com.nimbits.server.transaction.user.service.UserService;
import com.nimbits.server.transaction.value.service.ValueService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;


@RestController
public class RestAPI {


    private final TaskService taskService;
    private final ValueTask valueTask;
    private final BlobStore blobStore;
    private final SummaryService summaryService;
    private final SyncService syncService;
    private final SubscriptionService subscriptionService;
    private final CalculationService calculationService;
    private final DataProcessor dataProcessor;
    private final UserDao userDao;
    private final GeoSpatialDao geoSpatialDao;
    private final EntityService entityService;
    private final ValueService valueService;
    private final UserService userService;
    private final EntityDao entityDao;
    private final Gson gson;

    private final Logger logger = Logger.getLogger(RestAPI.class.getName());



    @Autowired
    public RestAPI(GeoSpatialDao geoSpatialDao, EntityService entityService, ValueService valueService, UserService userService,
                   EntityDao entityDao, TaskService taskService, ValueTask valueTask, BlobStore blobStore, SummaryService summaryService,
                   SyncService syncService, SubscriptionService subscriptionService, CalculationService calculationService, DataProcessor dataProcessor,
                   UserDao userDao) {

        this.taskService = taskService;
        this.valueTask = valueTask;
        this.blobStore = blobStore;
        this.summaryService = summaryService;
        this.syncService = syncService;
        this.subscriptionService = subscriptionService;
        this.calculationService = calculationService;
        this.dataProcessor = dataProcessor;
        this.userDao = userDao;
        this.geoSpatialDao = geoSpatialDao;
        this.entityService = entityService;
        this.valueService = valueService;
        this.userService = userService;
        this.entityDao = entityDao;
        this.gson = GsonFactory.getInstance(true);


    }


    @RequestMapping(value = "/{uuid}/file", method = RequestMethod.POST)
    public ResponseEntity postFile(
            @RequestHeader(name = "Authorization") String authorization,
            @RequestBody String json,
            @PathVariable String uuid) {
        getUser(authorization);
        logger.info("entered api: postFile");
        geoSpatialDao.addFile(uuid, json);
        return new ResponseEntity(HttpStatus.OK);
    }


    @RequestMapping(value = "/{uuid}/snapshot", method = RequestMethod.POST)
    public ResponseEntity postSnapshot(
            @RequestHeader(name = "Authorization") String authorization,
            @RequestBody String json,
            @PathVariable String uuid) throws Exception {
        logger.info("entered api: postSnapshot");
        User user = getUser(authorization);
        Point entity = (Point) entityDao.getEntity(user, uuid, EntityType.point);
        Value value = gson.fromJson(json, Value.class);
        taskService.process(geoSpatialDao, taskService, userService, entityDao,
                valueTask, entityService, blobStore, valueService, summaryService, syncService, subscriptionService,
                calculationService, dataProcessor, user, entity, value);
        return new ResponseEntity(HttpStatus.OK);


    }

    @RequestMapping(value = "/{uuid}/series", method = RequestMethod.POST)
    public ResponseEntity postSeries(
            @RequestHeader(name = "Authorization") String authorization,
            @RequestBody String json,
            @PathVariable String uuid) throws ValueException {

        logger.info("entered api: post series");
        Type listType = new TypeToken<ArrayList<Value>>() {
        }.getType();
        User user = getUser(authorization);
        Optional<Entity> optional = entityDao.getEntity(user, uuid, EntityType.point);
        List<Value> values = gson.fromJson(json, listType);

        if (optional.isPresent()) {
            if (values.size() == 1) {

                taskService.process(geoSpatialDao, taskService, userService, entityDao, valueTask, entityService, blobStore, valueService, summaryService, syncService, subscriptionService,
                        calculationService, dataProcessor, user, (Point) optional.get(), values.get(0));

            } else {

                valueService.recordValues(blobStore, user, (Point) optional.get(), values);
            }
        }
        return new ResponseEntity(HttpStatus.OK);


    }

    @RequestMapping(value = "/{uuid}", method = RequestMethod.POST)
    public ResponseEntity<String> postEntity( @RequestHeader(name = "Authorization") String authorization,
                                              @RequestBody String json,
                                              @PathVariable String uuid) throws IOException {
        logger.info("entered api: post entity");
        User user = getUser(authorization);
        EntityType type = getEntityType(json);
        Entity newEntity = (Entity) gson.fromJson(json, type.getClz());
        Optional<Entity> parentOptional = entityDao.findEntity(user, uuid);



        if (parentOptional.isPresent()) {
            Entity parent = parentOptional.get();
            //  EntityType type = e.getEntityType();
            //  Entity newEntity = (Entity) gson.fromJson(json, type.getClz());
            newEntity.setParent(parent.getId());
            newEntity.setOwner(user.getId());
            Entity stored = entityService.addUpdateEntity(valueService, user, newEntity);

            return new ResponseEntity<>(gson.toJson(stored), HttpStatus.OK);

        }
        else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        }

    }

    @RequestMapping(value = "/", method = RequestMethod.POST)
    public ResponseEntity<String> postUser( @RequestHeader(name = "Authorization") String authorization,
                                            @RequestBody String json) throws IOException {
        logger.info("entered api: add user");
        User user = getUser(authorization);

        if (user.getIsAdmin()) {
            User newUser = GsonFactory.getInstance(false).fromJson(json, UserModel.class);
            logger.info("creating user: " + json);
            User createdUser = userService.createUserRecord(entityService, valueService, newUser.getEmail(), newUser.getPassword(), UserSource.local);
            return new ResponseEntity<>(gson.toJson(createdUser), HttpStatus.OK);
        }
        else {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }




    }

    private EntityType getEntityType(@RequestBody String json) {

        Map jsonMap = gson.fromJson(json, Map.class);
        int t = Double.valueOf(String.valueOf(jsonMap.get("entityType"))).intValue();
        return EntityType.get(t);
    }




    @RequestMapping(value = "/{uuid}/file", method = RequestMethod.GET)
    public ResponseEntity<String> getFile( @RequestHeader(name = "Authorization") String authorization,@PathVariable String uuid) throws IOException {
        getUser(authorization);
        Optional<String> file = geoSpatialDao.getFile(uuid);
        if (file.isPresent()) {
            return new ResponseEntity<>(file.get(), HttpStatus.OK);

        }
        else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }




    //GET






    @RequestMapping(value = "/{uuid}/children", method = RequestMethod.GET)
    public ResponseEntity<String> getChildren(HttpServletRequest request,
                                              @RequestHeader(name = "Authorization") String authorization,
                                              @PathVariable String uuid ) throws IOException {
        logger.info("entered api: get children");
        User user = getUser(authorization);


        Optional<Entity> optional = entityDao.findEntity(user, uuid);


        if (optional.isPresent()) {
            List<Entity> children = entityDao.getChildren(user, Collections.singletonList(optional.get()));
            for (Entity e : children) {
                setHAL(user, e, Collections.<Entity>emptyList(), getCurrentUrl(request), null);
            }
            return new ResponseEntity<>(gson.toJson(children), HttpStatus.OK);
        }
        else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

    }


    private List<Entity> getChildEntitiesIfRequested(User user, boolean includeChildren) {


        List<Entity> children;
        if (includeChildren) {
            children = entityDao.getChildren(user, Collections.<Entity>singletonList(user));
        }
        else {
            children = Collections.emptyList();
        }
        return children;
    }



    @RequestMapping(value = "/{uuid}/series", method = RequestMethod.GET)
    public ResponseEntity<String> getSeries( @RequestHeader(name = "Authorization") String authorization,
                                             @PathVariable String uuid,
                                             @RequestParam(value = "start", required = false) String startParam,
                                             @RequestParam(value = "end", required = false) String endParam ,
                                             @RequestParam(value = "count", required = false) String countParam ,
                                             @RequestParam(value = "mask", required = false) String maskParam ) throws IOException {

        User user = getUser(authorization);

        logger.info("get series");
        Optional<String> mask = StringUtils.isEmpty(maskParam) ? Optional.<String>absent() : Optional.<String>of(maskParam);
        Date start = StringUtils.isEmpty(startParam) ? new Date(1) : new Date(Long.valueOf(startParam));
        Date end = StringUtils.isEmpty(endParam) ? new Date() : new Date(Long.valueOf(endParam));

        Optional<Integer> count = (StringUtils.isEmpty(countParam)) ? Optional.<Integer>absent() : Optional.of(Integer.valueOf(countParam));

        Optional<Range<Integer>> range;
        if (count.isPresent()) {
            range = Optional.of(Range.closed(0, count.get()));
        }
        else {
            range = Optional.absent();
        }

        Optional<Range<Date>> timespan = Optional.of(Range.closed(start, end));


        if (user.getId().equals(uuid)) {
            List<Entity> entities = entityDao.getEntitiesByType(user, EntityType.point);

            Map<String, List<Value>> map = new HashMap<>(entities.size());
            for (Entity e: entities) {
                List<Value>values = valueService.getSeries(blobStore, e, timespan, range, mask);

                map.put(e.getId(), values);


            }
            String resp = gson.toJson(map);
            return new ResponseEntity<>(resp, HttpStatus.OK);


        }
        else {
            Optional<Entity> optional = entityDao.getEntity(user, uuid, EntityType.point);
            if (optional.isPresent()) {


                List<Value> values = valueService.getSeries(blobStore, optional.get(), timespan, range, mask);

                String resp = gson.toJson(values);
                return new ResponseEntity<>(resp, HttpStatus.OK);
            }
            else {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
        }


    }

    @RequestMapping(value = "/{uuid}/table", method = RequestMethod.GET)
    public ResponseEntity<String> getTable( @RequestHeader(name = "Authorization") String authorization,
                                            @PathVariable String uuid,
                                            @RequestParam(value = "start", required = false) String startParam,
                                            @RequestParam(value = "end", required = false) String endParam ,
                                            @RequestParam(value = "count", required = false) String countParam ,
                                            @RequestParam(value = "mask", required = false) String maskParam ) throws IOException {

        User user = getUser(authorization);
        Optional<String> mask = StringUtils.isEmpty(maskParam) ? Optional.<String>absent() : Optional.of(maskParam);
        Optional<Integer> count = StringUtils.isNotEmpty(countParam) ? Optional.of(Integer.valueOf(countParam)) : Optional.<Integer>absent();

        Optional<Range<Date>> timespan;


        if (! StringUtils.isEmpty(startParam) && ! StringUtils.isEmpty(endParam) ) {
            Date start = new Date(Long.valueOf(startParam));
            Date end = new Date(Long.valueOf(endParam));
            timespan = Optional.of(Range.closed(start, end));

        }
        else {
            timespan = Optional.absent();
        }

        if (timespan.isPresent() || count.isPresent()) {

            Entity entity = entityDao.getEntity(user, uuid, EntityType.point).get();
            String chartData = valueService.getChartTable(entityDao, blobStore, user, entity, timespan, count, mask);
            return new ResponseEntity<>(chartData, HttpStatus.OK);

        }
        else {

            throw new RuntimeException(
                    "Please provide a start and end date parameter or a count parameter in unix epoch format including ms for example:?count=100 or ?count=100&mask=regex  or ?start="
                            + (System.currentTimeMillis() - 10000) + "&end=" + System.currentTimeMillis());
        }
    }

    @RequestMapping(value = "/{uuid}/snapshot", method = RequestMethod.GET)
    public ResponseEntity<String> getSnapshot( HttpServletRequest request,
                                               @RequestHeader(name = "Authorization") String authorization,
                                               @RequestParam(name = "sd", required = false) Long sd,
                                               @RequestParam(name = "sd", required = false) Long ed,
                                               @PathVariable String uuid) {
        logger.info("entered api: get snapshot");
        try {

            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.DAY_OF_YEAR, -1);


            User user = getUser(authorization);

            Self self = new Self(String.valueOf(getCurrentUrl(request) + uuid));
            Parent parent = new Parent(getCurrentUrl(request) + uuid);
            Sample sample = new Sample(getCurrentUrl(request) + uuid + "/snapshot", "get snapshot");

            Links links = new Links(self, parent, sample);


            EmbeddedValues valueEmbedded = null;
            if (sd != null && ed != null) {

                valueEmbedded = new EmbeddedValues(new ArrayList<Value>());

            }
            Optional<Entity> optional = entityDao.getEntity(user, uuid, EntityType.point);
            if (optional.isPresent()) {
                Value snapshot = valueService.getCurrentValue(blobStore, optional.get());
                ValueContainer valueContainer = new ValueContainer(links, valueEmbedded, snapshot);
                return new ResponseEntity<>(gson.toJson(valueContainer), HttpStatus.OK);
            }
            else {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
        } catch (Throwable ex)  {
            throw new RuntimeException(ex);
        }
    }

    private User getUser(String authString) {

        Optional<Credentials> credentials = userService.credentialsWithBasicAuthentication(authString);
        if (credentials.isPresent()) {
            Optional<User> user = userDao.getUserByEmail(credentials.get().getLogin());
            if (user.isPresent()) {
                if (userService.validatePassword(entityService, valueService, user.get(), credentials.get().getPassword())) {
                    return user.get();
                }
                else {

                    throw new SecurityException("Invalid Password: " + authString);
                }

            }
            else {
                throw new SecurityException("User Not Found: " + authString);
            }
        }
        else {
            throw new SecurityException("Invalid Credentials: " + authString);
        }


    }

    //Helper Methods
    @RequestMapping(value = "/{uuid}",  method = RequestMethod.GET)
    public ResponseEntity<String> getEntity(HttpServletRequest request,
            @RequestHeader(name = "Authorization") String authorization,
            @PathVariable String uuid,
            @RequestParam(name = "children", required = false) boolean includeChildren,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "point", required = false) String point,
            @RequestParam(name = "type", required = false) String t) throws IOException {

        logger.info("entered api: get entity");
        User user = getUser(authorization);
        String searchName = null;
        EntityType searchType;

        if (StringUtils.isNotEmpty(name) ) {
            searchName = name;
        }
        else  if (StringUtils.isNotEmpty(point) ) {
            searchName = name;
        }

        if (StringUtils.isEmpty(t)) {
            searchType = EntityType.point;
        }
        else {
            int type = Integer.valueOf(t);
            searchType = EntityType.get(type);
            if (searchType == null) {
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }
        }

        if (StringUtils.isNotEmpty(searchName)) {

            Optional<Entity> e =  entityDao.getEntityByName(user, CommonFactory.createName(name, searchType), searchType);
            if (e.isPresent()) {
                String json = gson.toJson(e.get());
                return new ResponseEntity<>(json, HttpStatus.OK);
            }
            else {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }

        }

        else if (uuid.equals("me")) {
            User u =  getMe(request, user, includeChildren);
            return new ResponseEntity<>(GsonFactory.getInstance(true).toJson(u), HttpStatus.OK);
        }


        else {
            Optional<Entity> optional = entityDao.findEntity(user, uuid);// entityMap.get(uuid);

            if (optional.isPresent()) {
                Entity entity = optional.get();


                List<Entity> children;
                if (includeChildren) {
                    children = entityDao.getChildren(user, Collections.singletonList(entity));
                } else {
                    children = Collections.emptyList();
                }

                setHAL(user, entity, children, getCurrentUrl(request), null);

                entity.setChildren(children);
                return new ResponseEntity<>(GsonFactory.getInstance(true).toJson(entity), HttpStatus.OK);
                //return new ResponseEntity<>(entity, HttpStatus.OK);

            } else {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
        }

    }


    private User getMe(HttpServletRequest request, User user, boolean withChildren) throws IOException {


        List<Entity> children = getChildEntitiesIfRequested(user, withChildren);
        Integer indx = user.getIsAdmin() ? 0 : null;
        setHAL(user, user, children, getCurrentUrl(request), null);
        user.setChildren(children);
        return user;

    }

    private String getCurrentUrl(HttpServletRequest request) {
        URL url;
        try {
            url = new URL(request.getRequestURL().toString());

            String host  = url.getHost();
            String userInfo = url.getUserInfo();
            String scheme = url.getProtocol();
            int port = url.getPort();
            String path = (String) request.getAttribute("javax.servlet.forward.request_uri");
            String query = (String) request.getAttribute("javax.servlet.forward.query_string");

            URI uri = new URI(scheme,userInfo,host,port,path,query,null);
            return uri.toString() + "/service/v3/rest/";
        } catch (MalformedURLException e) {
            return e.getMessage();
        } catch (URISyntaxException e) {
            return e.getMessage();
        }
    }



    //DELETE

    @RequestMapping(value = "/{uuid}", method = RequestMethod.DELETE)
    public ResponseEntity doDelete( @RequestHeader(name = "Authorization") String authorization,
                                    @PathVariable String uuid) throws IOException {
        logger.info("entered api: delete entity");

        User user = getUser(authorization);
        Optional<Entity> optional = entityDao.findEntity(user, uuid);
        if (optional.isPresent()) {
            Entity entity = optional.get();
            if (!user.getIsAdmin() && entity.getEntityType() != EntityType.user && entity.getOwner().equals(user.getId())) {
                entityService.deleteEntity(user, entity);
            } else if (user.getIsAdmin()) {
                entityService.deleteEntity(user, entity);
            } else if (!entity.getOwner().equals(user.getId())) {
                throw new SecurityException("You can not delete an entity you don't own if your not the system admin");
            }
            return new ResponseEntity(HttpStatus.OK);
        }
        else {
            return new ResponseEntity(HttpStatus.NOT_FOUND);
        }
    }


    //PUT
    @RequestMapping(value = "/{uuid}", method = RequestMethod.PUT)
    public ResponseEntity putEntity(
            @RequestHeader(name = "Authorization") String authorization,
            @RequestBody String json) {


        User user = getUser(authorization);
        EntityType type = getEntityType(json);
        Entity entity = (Entity) gson.fromJson(json, type.getClz());

        entityService.addUpdateEntity(valueService, user, entity);
        return new ResponseEntity(HttpStatus.OK);


    }

    @RequestMapping(value = "/", method = RequestMethod.PUT)
    public ResponseEntity putUser( @RequestHeader(name = "Authorization") String authorization, @RequestBody User update)  {

        logger.info("entered api: update user");
        User user = getUser(authorization);
        if (user.getIsAdmin()) {
            if (!StringUtils.isEmpty(update.getPassword())) {


                userService.updatePassword(update, update.getPassword());

            } else {

                entityService.addUpdateEntity(valueService, user, update);
            }
            return new ResponseEntity(HttpStatus.OK);
        }
        else {
            return new ResponseEntity(HttpStatus.UNAUTHORIZED);
        }

    }

    private void setHAL(User user, Entity entity, List<Entity> childList, String path, Integer index) {


        Parent parent;
        if (entity.getEntityType().equals(EntityType.user)) {
            parent = new Parent(path + user.getId());
        }
        else {
            Optional<Entity> rootParentEntity = entityDao.findEntity(user, entity.getParent());
            if (rootParentEntity.isPresent()) {
                parent = new Parent(path + rootParentEntity.get().getId());
            }
            else {
                parent = new Parent(path + user.getId());
            }
        }


        Self self = new Self(path + entity.getId());
        Series series = null;
        DataTable dataTable = null;
        Snapshot snapshot = null;
        Next next = null;
        Nearby nearby = null;
        Children children;


        if (entity.getEntityType().equals(EntityType.point)) {
            Point point = (Point) entity;
            series =new Series(path + entity.getId() + "/series");
            dataTable =new DataTable(path + entity.getId() + "/table");
            snapshot =new Snapshot(path + entity.getId() + "/snapshot");
            if (point.getPointType().equals(PointType.location)) {
                nearby = new Nearby(path + entity.getId() + "/nearby");
            }


        }
        else if (entity.getEntityType().equals(EntityType.user)) {
            series =new Series(path + entity.getId() + "/series");
            if (index != null) {
                next = new Next(path.substring(0, path.lastIndexOf("/")) + "?index=" + ++index);
            }


        }
        children =new Children(path + entity.getId() + "/children");
        Links links = new Links(self, parent, series, snapshot, dataTable, next, nearby, children);
        List<EntityChild> entityChildren = new ArrayList<>();



        for (Entity child : childList) {
            if (child.getParent().equals(entity.getId()) && ! child.getId().equals(entity.getId())) {

                Self eSelf = new Self(path + child.getId());
                Series cseries = null;
                DataTable cdataTable = null;
                Snapshot csnapshot = null;
                Nearby cnearby = null;
                Children cchildren;

                if (child.getEntityType().equals(EntityType.point)) {
                    cseries =new Series(path + child.getId() + "/series");
                    cdataTable =new DataTable(path + child.getId() + "/table");
                    csnapshot  =new Snapshot(path + child.getId() + "/snapshot");
                    Point point1 = (Point) child;
                    if (point1.getPointType().equals(PointType.location)) {
                        cnearby = new Nearby(path + child.getId() + "/nearby");
                    }
                }
                //  Entity parentEntity = childMap.get(child.getParent());

                Parent eParent;
                if (child.getParent().equals(user.getId())) {
                    eParent = new Parent(path + "me");
                }
                else {
                    eParent  = new Parent(path + entity.getId());
                }
                cchildren  =new Children(path + entity.getId() + "/children");
                Links eLinks = new Links(eSelf, eParent, cseries, csnapshot, cdataTable, null, cnearby, cchildren);

                entityChildren.add(new EntityChild(eLinks, child.getName().getValue()));
            }

        }
        Embedded embedded = new Embedded(entityChildren);
        entity.setEmbedded(embedded);

        entity.setLinks(links);
    }

}

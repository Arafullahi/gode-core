package org.ibs.cds.gode.entity.manager;

import com.querydsl.core.types.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.ibs.cds.gode.entity.cache.repo.CacheableEntityRepo;
import org.ibs.cds.gode.entity.manager.operation.PureStateEntityManagerOperation;
import org.ibs.cds.gode.entity.repo.Repo;
import org.ibs.cds.gode.entity.repo.RepoType;
import org.ibs.cds.gode.entity.store.repo.StoreEntityRepo;
import org.ibs.cds.gode.entity.type.TypicalEntity;
import org.ibs.cds.gode.entity.validation.ValidationStatus;
import org.ibs.cds.gode.entity.view.EntityView;
import org.ibs.cds.gode.exception.KnownException;
import org.ibs.cds.gode.pagination.PageContext;
import org.ibs.cds.gode.pagination.PagedData;
import org.ibs.cds.gode.status.BinaryStatus;
import org.ibs.cds.gode.system.AppId;
import org.ibs.cds.gode.util.PageUtils;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public abstract class PureEntityManager<Entity extends EntityView<Id>,
        Id extends Serializable> extends EntityViewManager<Entity, Id>
        implements PureStateEntityManagerOperation< Entity, Id> {

    protected static final String SAVE = "save";
    protected static final String BFR_SAVE = "beforeSave";
    protected static final String AFR_SAVE = "afterSave";
    protected static final String DO_SAVE = "doSave";
    protected static final String FIND_BY_ID = "findById";
    protected static final String FIND_BY_APPID = "findByAppId";
    protected static final String VALIDATE = "validate";
    protected static final String FIND_ALL = "findAll";
    protected static final String FIND_ALL_BY_ID = "findAllById";
    protected static final String FIND_BY_PREDICATE = "findByPredicate";
    protected static final String DEACTIVATE = "deactivate";
    protected static final String LOG_TEMPLATE = "Action : {} | Arguments: {}";
    protected static final String LOG_TEMPLATE2 = "Action : {} | Arguments: {},{}";

    protected EntityRepository<Entity, Id> repository;

    public <StoreRepo extends StoreEntityRepo<Entity, Id>, CacheRepo extends CacheableEntityRepo<Entity, Id>> PureEntityManager(
            StoreRepo storeEntityRepo,
            CacheRepo cacheableEntityRepo) {
        repository = new EntityRepository();
        repository.add(storeEntityRepo);
        repository.add(cacheableEntityRepo);
    }

    private boolean isNewEntity(Entity entity) {
        return entity.getCreatedOn() != null && entity.getUpdatedOn() != null && entity.getCreatedOn().equals(entity.getCreatedOn());
    }

    private void setDefaultFields(Entity entity) {
        Date now = new Date();
        setDefaultFields(entity, now);
    }

    private void setDefaultFields(Entity entity, Date time) {
        if (entity.getCreatedOn() == null) entity.setCreatedOn(time);
        entity.setUpdatedOn(time);
        if (entity.getAppId() == null || entity.getAppId().compareTo(0L) == 0) entity.setAppId(AppId.next());
    }

    public Optional<Entity> beforeSave(Optional<Entity> entity) {
        entity.ifPresent(e -> {
            log.debug(LOG_TEMPLATE, "defaultFieldSetting", e);
            setDefaultFields(e);
            log.debug(LOG_TEMPLATE, BFR_SAVE, e);
            invokeValidation(e, this::validateEntity);
        });
        return entity;
    }

    public Optional<Entity> afterSave(Optional<Entity> entity) {
        log.debug(LOG_TEMPLATE, AFR_SAVE, entity);
        return entity;
    }

    public Optional<Entity> doSave(Optional<Entity> entity) {
        log.debug(LOG_TEMPLATE, DO_SAVE, entity);
        return operateAll(entity, (r, e) -> e.map(ep -> r.save(ep)));
    }


    protected <T, A> T operateAll(A argument, BiFunction<Repo<Entity, Id>, A, T> perform) {
        return this.repository.values().stream()
                .map(repo -> perform.apply(repo, argument))
                .filter(Objects::nonNull)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    protected <T, A> T operateFirst(A argument, BiFunction<Repo<Entity, Id>, A, T> perform) {
        return this.repository.values().stream()
                .map(repo -> perform.apply(repo, argument))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private Entity repoSave(Repo<Entity, Id> repo, Entity entity) {
        log.debug("Repository save for {} with {}", entity, repo.getClass().getSimpleName());
        return repo.save(entity);
    }

    @Override
    public Entity save(Entity view) {
        invokeValidation(view, this::validateView);
        log.debug(LOG_TEMPLATE, SAVE, view);
        return afterSave(doSave(beforeSave(Optional.ofNullable(view)))).orElse(null);
    }

    protected <T extends TypicalEntity<?>> void invokeValidation(T entity, Function<T, ValidationStatus> validationFunction) {
        log.debug(LOG_TEMPLATE, VALIDATE, entity);
        if (!entity.isValidated()) {
            ValidationStatus viewValidation = validationFunction.apply(entity);
            if (viewValidation.getStatus() == BinaryStatus.FAILURE) {
                throw KnownException.ENTITY_VALIDATIONS_FAILED.provide(viewValidation.getErrors());
            }
            entity.setValidated(true);
        }
    }

    @Override
    public Entity findByAppId(Long appId) {
        log.debug(LOG_TEMPLATE, FIND_BY_APPID, appId);
        return operateFirst(appId, (r, i) -> r.findByAppId(i)).orElse(null);
    }

    @Override
    public Entity find(Id id) {
        log.debug(LOG_TEMPLATE, FIND_BY_ID, id);
        return findById(id).orElse(null);
    }

    protected Optional<Entity> findById(Id id) {
        return operateFirst(id, (r, i) -> r.findById(i));
    }

    @Override
    public boolean deactivate(Id id) {
        log.debug(LOG_TEMPLATE, DEACTIVATE, id);
        return operateAll(id, (r, i) -> deactivateRepo(r, i) != null);
    }

    public PagedData<Entity> find(PageContext context) {
        log.debug(LOG_TEMPLATE, FIND_ALL, context);
        return operateFirst(context, (r, c) -> r.findAll(c));
    }

    public List<Entity> find(List<Id> ids) {
        log.debug(LOG_TEMPLATE, FIND_ALL_BY_ID, ids);
        List<Entity> entities = operateFirst(ids, (r, idList) -> r.findByIdIn(idList));
        return entities == null ? List.of() :
                entities.stream()
                        .map(Optional::ofNullable)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toList());
    }

    public PagedData<Entity> find(Predicate predicate, PageContext pageContext) {
        log.debug(LOG_TEMPLATE2, FIND_BY_PREDICATE, predicate, pageContext);
        Repo<Entity, Id> entityIdRepo = repository.get(RepoType.STORE);
        if (entityIdRepo != null) {
            return ((StoreEntityRepo<Entity, Id>) entityIdRepo).findAll(predicate, pageContext);
        }
        return PageUtils.emptyPage();
    }

    private Entity deactivateRepo(Repo<Entity, Id> r, Id i) {
        return r.findById(i)
                .map(x -> {
                    x.setActive(false);
                    return r.save(x);
                }).orElse(null);
    }
}

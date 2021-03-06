package cn.zk.service.impl;

import cn.zk.app.config.CuratorManagerProperties;
import cn.zk.common.AdminException;
import cn.zk.common.RespCode;
import cn.zk.entity.PathDataVO;
import cn.zk.entity.PathVO;
import cn.zk.entity.ZkInfo;
import cn.zk.manager.AbstractCuratorManager;
import cn.zk.manager.DefaultCuratorManager;
import cn.zk.manager.factory.CuratorManagerFactory;
import cn.zk.manager.observer.ConnStateObserver;
import cn.zk.repository.ZkInfoRepository;
import cn.zk.service.ZkInfoService;
import cn.zk.util.StringUtils;
import cn.zk.websocket.ZkStateMessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * <br/>
 * Created on 2018/6/12 15:35.
 *
 * @author zhubenle
 */
@Slf4j
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Service
public class ZkInfoServiceImpl implements ZkInfoService {

    private final static String SEPARATOR = "/";

    private final ZkInfoRepository zkInfoRepository;
    private final CuratorManagerFactory curatorManagerFactory;
    private final CuratorManagerProperties curatorClientProperties;
    private final ZkStateMessageHandler zkStateMessageHandler;

    @Override
    public List<ZkInfo> listAll() {
        return zkInfoRepository.findAll();
    }

    @Override
    public void saveZkInfo(ZkInfo zkInfo) {
        DefaultCuratorManager curatorManager = new DefaultCuratorManager(zkInfo.getHosts(), curatorClientProperties);
        curatorManager.addObserver(new ConnStateObserver(this, zkStateMessageHandler));
        curatorManagerFactory.getManagerMap().put(zkInfo.getAlias(), curatorManager);

        zkInfoRepository.save(zkInfo);
    }

    @Override
    public void updateZkInfoConnStateByHosts(String hosts, String connState) {
        zkInfoRepository.updateConnStateByHosts(hosts, connState);
    }

    @Override
    public void deleteZkInfoByAlias(String alias) {
        if (StringUtils.isEmpty(alias)) {
            throw new AdminException(RespCode.ERROR_10004);
        }
        curatorManagerFactory.removeManager(alias);
        zkInfoRepository.deleteZkInfoByAliasEquals(alias);
        log.info("删除ZkInfo, alias={}", alias);
    }

    @Override
    public void reconnectZk(String alias) {
        if (StringUtils.isEmpty(alias)) {
            throw new AdminException(RespCode.ERROR_10004);
        }
        Optional<AbstractCuratorManager> abstractCuratorManager = curatorManagerFactory.getManager(alias);
        if (abstractCuratorManager.isPresent() && abstractCuratorManager.get().isConnected()) {
            throw new AdminException(RespCode.ERROR_10006);
        }
        curatorManagerFactory.removeManager(alias);

        ZkInfo zkInfo = zkInfoRepository.getZkInfoByAliasEquals(alias);
        DefaultCuratorManager curatorManager = new DefaultCuratorManager(zkInfo.getHosts(), curatorClientProperties);
        curatorManager.addObserver(new ConnStateObserver(this, zkStateMessageHandler));
        curatorManagerFactory.getManagerMap().put(zkInfo.getAlias(), curatorManager);
    }

    @Override
    public List<PathVO> listZkChildrenPath(String alias, String pathId) {
        DefaultCuratorManager curatorManager = (DefaultCuratorManager) curatorManagerFactory.getManager(alias)
                .orElseThrow(() -> new AdminException(RespCode.ERROR_10003));
        String tempPathId = StringUtils.isEmpty(pathId) ? SEPARATOR : pathId;
        List<PathVO> result = curatorManager.listChildrenPath(tempPathId)
                .stream()
                .map(s -> {
                    PathVO pathVO = new PathVO(s, curatorManager.getPathStat(tempPathId + (SEPARATOR.equals(tempPathId) ? "" : SEPARATOR) + s).getNumChildren() > 0);
                    pathVO.setId(tempPathId + (SEPARATOR.equals(tempPathId) ? "" : SEPARATOR) + s);
                    return pathVO;
                })
                .sorted((o1, o2) -> o1.getName().compareToIgnoreCase(o2.getName())).collect(Collectors.toList());

        if (StringUtils.isEmpty(pathId)) {
            List<PathVO> temp = new ArrayList<>();
            PathVO pathVO = new PathVO(tempPathId, true);
            pathVO.setOpen(true);
            pathVO.setChildren(result);
            pathVO.setId(tempPathId);
            temp.add(pathVO);
            result = temp;
        }
        return result;
    }

    @Override
    public void deletePath(String alias, String pathId, Integer dataVersion) {
        if (Objects.isNull(dataVersion)) {
            throw new AdminException(RespCode.ERROR_10004);
        }
        DefaultCuratorManager curatorManager = (DefaultCuratorManager) curatorManagerFactory.getManager(alias)
                .orElseThrow(() -> new AdminException(RespCode.ERROR_10003));
        curatorManager.deletePath(pathId, dataVersion);
    }

    @Override
    public String createPath(String alias, String pathId, String data, Integer createMode) {
        if (Objects.isNull(pathId)) {
            throw new AdminException(RespCode.ERROR_10004);
        }
        DefaultCuratorManager curatorManager = (DefaultCuratorManager) curatorManagerFactory.getManager(alias)
                .orElseThrow(() -> new AdminException(RespCode.ERROR_10003));
        return curatorManager.createPath(pathId, data, null, createMode);
    }

    @Override
    public String updatePath(String alias, String newPathId, String oldPathId, String data, Integer version, Integer createMode) {
        DefaultCuratorManager curatorManager = (DefaultCuratorManager) curatorManagerFactory.getManager(alias)
                .orElseThrow(() -> new AdminException(RespCode.ERROR_10003));
        if (newPathId.equals(oldPathId)) {
            //更新节点数据
            curatorManager.setPathData(oldPathId, data, version);
            return oldPathId;
        }
        //添加新节点，删除旧节点
        String pathId = curatorManager.createPath(newPathId, data, null, createMode);
        curatorManager.deletePath(oldPathId, version);
        return pathId;
    }

    @Override
    public PathDataVO getPathData(String alias, String pathId) {
        DefaultCuratorManager curatorManager = (DefaultCuratorManager) curatorManagerFactory.getManager(alias)
                .orElseThrow(() -> new AdminException(RespCode.ERROR_10003));
        Stat stat = new Stat();
        String result = curatorManager.getPathData(pathId, stat);
        return new PathDataVO(stat, result);
    }

    @Override
    public void copyPastePath(String alias, String copy, String paste, String newBasePaste) {
        DefaultCuratorManager curatorManager = (DefaultCuratorManager) curatorManagerFactory.getManager(alias)
                .orElseThrow(() -> new AdminException(RespCode.ERROR_10003));
        if (SEPARATOR.equals(copy)) {
            throw new AdminException(RespCode.ERROR_10007);
        }
        if (!SEPARATOR.equals(paste)) {
            paste = paste + SEPARATOR;
        }
        log.info("alias={}, copy={}, paste={}", alias, copy, paste);
        paste = paste + (StringUtils.isEmpty(newBasePaste) ? copy.substring(copy.lastIndexOf(SEPARATOR) + 1) : newBasePaste);
        if (curatorManager.checkPathExist(paste)) {
            throw new AdminException(RespCode.ERROR_10008);
        }
        copyPaste(copy, paste, curatorManager);
    }

    private void copyPaste(String copy, String paste, DefaultCuratorManager curatorManager) {
        curatorManager.createPath(paste, curatorManager.getPathData(copy), null, CreateMode.PERSISTENT.toFlag());
        curatorManager.listChildrenPath(copy).forEach(s -> {
            copyPaste(copy + SEPARATOR + s, paste + SEPARATOR + s, curatorManager);
        });
    }
}

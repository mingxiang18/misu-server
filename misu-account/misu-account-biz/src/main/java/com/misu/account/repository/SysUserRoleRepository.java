package com.misu.account.repository;

import com.misu.account.domain.entity.SysUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.Collection;
import java.util.List;

public interface SysUserRoleRepository extends JpaRepository<SysUserRole, Long>, JpaSpecificationExecutor<SysUserRole>, QuerydslPredicateExecutor<SysUserRole> {

    List<SysUserRole> findByUserIdIn(Collection<Long> userIds);

    List<SysUserRole> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}

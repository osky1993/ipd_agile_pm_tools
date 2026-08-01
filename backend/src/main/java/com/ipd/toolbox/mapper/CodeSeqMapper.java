package com.ipd.toolbox.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CodeSeqMapper {

    @Select("SELECT next_val FROM code_seq WHERE project_id = #{projectId} AND type_abbr = #{abbr} FOR UPDATE")
    Integer selectForUpdate(@Param("projectId") Long projectId, @Param("abbr") String abbr);

    @Insert("INSERT INTO code_seq(project_id, type_abbr, next_val) VALUES(#{projectId}, #{abbr}, #{val})")
    void insert(@Param("projectId") Long projectId, @Param("abbr") String abbr, @Param("val") int val);

    @Update("UPDATE code_seq SET next_val = #{val} WHERE project_id = #{projectId} AND type_abbr = #{abbr}")
    void update(@Param("projectId") Long projectId, @Param("abbr") String abbr, @Param("val") int val);
}

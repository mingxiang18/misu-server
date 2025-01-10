package com.misu.common.util;

import lombok.SneakyThrows;

import java.lang.reflect.Field;

public class QuerydslDaoGenerationUtil {

    @SneakyThrows
    public static void generateUpdate(Class<?> clazz, String modelName) {
        Field[] fields = clazz.getDeclaredFields();
        StringBuilder code = new StringBuilder();
        code.append("JPAUpdateClause updateClause = jpaQueryFactory\n" +
                "        .update(" + modelName + ");\n");

        // Create a map of field names and their values
        for (Field field : fields) {
            String getFieldName = "entity.get" + field.getName().substring(0, 1).toUpperCase() + field.getName().substring(1)+ "()";

            code.append("if (" + getFieldName + " != null) {\n");
            //执行get方法，字段名第一个字母要转成大写
            code.append("    updateClause.set(" + modelName + "." + field.getName() + ", " + getFieldName + ");\n");
            code.append("}\n");
        }
        code.append("return updateClause\n" +
                "        .where(" + modelName + ".id.eq(entity.getId()))\n" +
                "        .execute();");
        System.out.println(code);
    }
}

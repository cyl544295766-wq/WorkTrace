#!/bin/sh
# 加载 MySQL 时区表。
# 应用 JDBC 使用 connectionTimeZone=Asia/Shanghai，Connector/J 建连时会执行
# SET time_zone='Asia/Shanghai'；实例没有时区表会报
# "Unknown or incorrect time zone: 'Asia/Shanghai'"，连接直接失败。
set -e

echo "Loading timezone tables from /usr/share/zoneinfo ..."
mysql_tzinfo_to_sql /usr/share/zoneinfo | mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" mysql
echo "Timezone tables loaded."

from pydantic import BaseModel
from typing import Optional
from .sql_common import SQLAlchemyConfig, get_sql_workunits
from gometa.ingestion.api.source import Source

class MySQLConfig(SQLAlchemyConfig):
    #defaults
    host_port = "localhost:3306"
    scheme = "mysql+pymysql"

class MySQLSource(Source):
    def configure(self, config_dict):
        self.config = MySQLConfig.parse_obj(config_dict)

    def get_workunits(self):
        return get_sql_workunits(self.config, "mysql")
        
    def close(self):
        pass
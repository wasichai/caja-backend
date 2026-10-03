package caja.comun

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.data.PhysicalTableRecordStore
import wasichai.core.data.RecordStore
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.platform.WasichaiSchemas

// el RecordStore de la app: el de wasichai (PhysicalTableRecordStore, el mismo que da su autoconfiguración, que es
// @ConditionalOnMissingBean: WasichaiDataAutoConfiguration) dentro de la guarda de caja, como AlmacenDeRegistros de
// srtm. RecordService, la API genérica y el admin escriben por él. su límite: si otro módulo decorara también el
// RecordStore, este bean lo dejaría fuera; hoy no hay ninguno
@Configuration(proxyBeanMethods = false)
class AlmacenDeRegistros {
    @Bean
    fun recordStore(
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        types: FieldTypeRegistry
    ): RecordStore = GuardiaDeEscrituras(PhysicalTableRecordStore(db, schemas, types))
}

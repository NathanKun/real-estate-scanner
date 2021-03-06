package com.catprogrammer.cogedimscanner.entity

import com.catprogrammer.cogedimscanner.model.RealEstateDeveloper
import com.fasterxml.jackson.annotation.JsonView
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime
import javax.persistence.*

@Entity
data class Program(
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        val id: Long?,
        var developer: RealEstateDeveloper,
        val programName: String,
        val programNumber: String,
        val postalCode: String,
        val address: String,
        val url: String,
        val imgUrl: String,
        val pdfUrl: String?,
        val latitude: String,
        val longitude: String,
        @JsonView(LotsView::class)
        @OneToMany(fetch = FetchType.EAGER)
        val lots: MutableList<Lot>,
        @CreationTimestamp
        val createdAt: LocalDateTime?,
        @UpdateTimestamp
        val modifiedAt: LocalDateTime?
) {
    interface SimpleView
    interface LotsView : SimpleView
}

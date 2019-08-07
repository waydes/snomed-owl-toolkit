package org.snomed.otf.owltoolkit.conversion;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.ihtsdo.otf.snomedboot.factory.ImpotentComponentFactory;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.otf.owltoolkit.constants.Concepts;
import org.snomed.otf.owltoolkit.constants.RF2Headers;
import org.snomed.otf.owltoolkit.domain.Relationship;
import org.snomed.otf.owltoolkit.taxonomy.SnomedTaxonomy;
import org.snomed.otf.owltoolkit.util.InputStreamSet;
import org.snomed.otf.owltoolkit.util.OptionalFileInputStream;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Used to convert extension stated relationships having international concepts as source to 
 * primitive additional OWL Axiom reference set.
 * The following cases found in the US extension:
 * 1. Adding just Is-A
 * 2. Adding Is-A and not grouped (group 0) attributes
 * 3. Adding not grouped non Is-A only
 * 4. Adding grouped attribute (non zero role group)
 * 
 * Replacing above with additional axioms:
 * 
 *  I. Create primitive additional axioms for Case 1 and 2 in the extension module.
 *  		Inputs: The International Complete OWL release snapshot files and Extension Snapshot export from the termServer 
 *  
 *  II. Case 3 requires adding an Is_A manually + existing not grouped Is-A to create primitive additional axiom in the extension module only.
 *  		Inputs: Same as above after manually adding the appropriate Is-A and patching the Extension Snapshot files
 *  
 *  III. Case 4 requires the international stated view to create additional axiom.
 *  		Inputs: The International stated build used for complete OWL conversion + Extension Snapshot export
 *	
 * Outputs: Inactive stated relationships and OWL axioms reference set
 * 
 * Note: Axioms must be primitive when created for I and II
 * How to run:
 * To generate report:
 * -rf2-stated-to-complete-owl -rf2-snapshot-archives <INT complete owl>, <Extension snapshot export> -analyze
 * 
 * To create axioms:
 * -rf2-stated-to-complete-owl -rf2-snapshot-archives <INT complete owl>, <Extension snapshot export> -additional
 *
 */
public class AdditionalExtensionStatedRelationshipToOwlRefsetService extends StatedRelationshipToOwlRefsetService {

	private final Logger logger = LoggerFactory.getLogger(getClass());
	
	public void checkAndReportExtensionOverridingInternational(InputStreamSet snapshotInputStreamSet, OutputStream rf2DeltaZipResults,
			String effectiveDate) throws IOException, ConversionException {
		
		ExtensionOverridingInternationalProcessor processor = new ExtensionOverridingInternationalProcessor();
		SnomedTaxonomy snomedTaxonomy = readSnomedTaxonomy(snapshotInputStreamSet, new OptionalFileInputStream(null), processor, null);
		processor.complete();
		logger.info("Total active stated relationships having international source and destination concepts:" + processor.getTotalSourceAndDestinationAreIntConcepts());
		Set<Long> bothIntConcepts = processor.getBothConceptsAreInternational();
		writeOutStatedRelationshipsToFile(snomedTaxonomy, bothIntConcepts, "StatedRelationshipsToBeConvertedUsingAdditionalAxioms.txt");
		logger.info("See full list in StatedRelationshipsToBeConvertedUsingAdditionalAxioms.txt");
		logger.info("Total active stated relationships having international source concept only:" + processor.getInternationalConceptsModifiedByExtension().size());
		Set<Long> extensionDestinationConcepts = processor.getInternationalConceptsModifiedByExtension();
		extensionDestinationConcepts.removeAll(processor.getBothConceptsAreInternational());
		writeOutStatedRelationshipsToFile(snomedTaxonomy, extensionDestinationConcepts, "StatedRelationshipToBeConvertedUsingGCIs.txt");
		logger.info("See full list in StatedRelationshipToBeConvertedUsingGCIs.txt");
		
		writeOutStatedRelationshipsToFile(snomedTaxonomy, processor.getConceptsToGenerateAxiomsWithoutIntModelling(), "StatedRelationshipsToAxiomsWithoutIntStatedView.txt");
		logger.info("Total concepts to create axioms without Internatinal model:" + processor.getConceptsToGenerateAxiomsWithoutIntModelling().size());
		logger.info("See full list in StatedRelationshipsToAxiomsWithoutIntStatedView.txt");
		
		writeOutStatedRelationshipsToFile(snomedTaxonomy, processor.getConceptsToGenerateAxiomsNeedToAddIsA(), "StatedRelationshipsToAxiomsToAddIsA.txt");
		logger.info("Total concepts to create axioms with the need to add IS_A manually:" + processor.getConceptsToGenerateAxiomsNeedToAddIsA().size());
		logger.info("See full list in StatedRelationshipsToAxiomsToAddIsA.txt");
		
		writeOutStatedRelationshipsToFile(snomedTaxonomy, processor.getConceptsToGenerateAxiomsWithIntModelling(), "StatedRelationshipsToAxiomsRequiringIntStatedView.txt");
		logger.info("Total concepts to create axioms requiring International modelling:" + processor.getConceptsToGenerateAxiomsWithIntModelling().size());
		logger.info("See full list in StatedRelationshipsToAxiomsRequiringIntStatedView.txt");
	}

	public void convertExtensionStatedRelationshipsToAdditionalAxioms(InputStreamSet snapshotInputStreamSet,
			OutputStream rf2DeltaZipResults,
			String effectiveDate) throws ConversionException, OWLOntologyCreationException, IOException {
		
		try (ZipOutputStream zipOutputStream = new ZipOutputStream(rf2DeltaZipResults)) {
			zipOutputStream.putNextEntry(new ZipEntry(SCT2_STATED_RELATIONSHIP_DELTA + effectiveDate + TXT));
			ExtensionOverridingInternationalProcessor processor = new ExtensionOverridingInternationalProcessor(zipOutputStream);
			SnomedTaxonomy snomedTaxonomy = readSnomedTaxonomy(snapshotInputStreamSet, new OptionalFileInputStream(null), processor, null);
			processor.complete();
			zipOutputStream.closeEntry();
			zipOutputStream.putNextEntry(new ZipEntry(OWL_AXIOM_REFSET_DELTA + effectiveDate + TXT));
			//uncomment code below to generate primitive axioms using International complete owl
			Set<Long> conceptsToGenerate = processor.getConceptsToGenerateAxiomsWithoutIntModelling();
//			Set<Long> conceptsToGenerate = processor.getConceptsToGenerateAxiomsNeedToAddIsA();
			convertStatedRelationshipsToOwlRefsetForExtension(snomedTaxonomy, conceptsToGenerate, zipOutputStream, processor.getExtensionModuleId(), true);
			
			//uncomment code below to generate concepts requiring INT stated view
//			Set<Long> conceptsToGenerate = processor.getConceptsToGenerateAxiomsWithIntModelling();
//			convertStatedRelationshipsToOwlRefsetForExtension(snomedTaxonomy, conceptsToGenerate, zipOutputStream, processor.getExtensionModuleId());
			zipOutputStream.closeEntry();
		}
	}
	
	
	private void writeOutStatedRelationshipsToFile(SnomedTaxonomy snomedTaxonomy, Set<Long> concepts, String filename) throws IOException {
		File bothConceptsReport = new File(filename);
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(bothConceptsReport))) {
			writer.write(RF2Headers.RELATIONSHIP_HEADER);
			writer.newLine();
			for (Long conceptId : concepts) {
				for (Relationship rel : snomedTaxonomy.getStatedRelationships(conceptId)) {
					writeStateRelationshipRow(writer, String.valueOf(rel.getRelationshipId()), 
							"1", String.valueOf(rel.getModuleId()), String.valueOf(conceptId),
							String.valueOf(rel.getDestinationId()), String.valueOf(rel.getGroup()), 
							String.valueOf(rel.getTypeId()));
				}
			}
		}
	}
	
	private static class ExtensionOverridingInternationalProcessor extends ImpotentComponentFactory {
		
		private static final String IS_A = "116680003";
		private Set<Long> activeInternationalConcepts = new LongOpenHashSet();
		private String extensionModuleId = null;
		private Set<Long> sourceConcepts = new LongOpenHashSet();
		private Set<Long> sourceAndDestinatinConcepts = new LongOpenHashSet();
		private int totalSourceAndDestinationAreIntConcepts = 0;
		private final BufferedWriter writer;
		private final List<IOException> exceptionsThrown = new ArrayList<>();
		private Set<Long> hasIsAConcepts = new LongOpenHashSet();
		private Set<Long> hasNonIsAConcepts = new LongOpenHashSet();
		private Map<Long, Set<String>> nonIsaRelationGroupMap = new HashMap<Long, Set<String>>();
		
		Set<Long> requiringIntModel = new HashSet<>();
		Set<Long> withoutIntModel = new HashSet<>();
		Set<Long> toAddIsAOnly = new HashSet<>();
		
		public ExtensionOverridingInternationalProcessor() {
			writer = null;
		}
		
		public Set<Long> getConceptsToGenerateAxiomsWithIntModelling() {
			return this.requiringIntModel;
		}

		public Set<Long> getConceptsToGenerateAxiomsNeedToAddIsA() {
			return this.toAddIsAOnly;
		}

		public Set<Long> getConceptsToGenerateAxiomsWithoutIntModelling() {
			return this.withoutIntModel;
		}
		
		public void analyze() {
			Set<Long> conceptsOnlyAddedIsA = new HashSet<>(getConceptsHavingIsARelationship());
			conceptsOnlyAddedIsA.removeAll(getConceptsHavingNonIsARelationship());
			withoutIntModel.addAll(conceptsOnlyAddedIsA);
			
			Set<Long> conceptsHavingBoth = new HashSet<>(getConceptsHavingIsARelationship());
			conceptsHavingBoth.retainAll(getConceptsHavingNonIsARelationship());
			
			for (Long concept : conceptsHavingBoth) {
				boolean grouped = isGrouped(concept);
				if (grouped) {
					requiringIntModel.add(concept);
				} else {
					withoutIntModel.add(concept);
				}
			}
			Set<Long> conceptsHavingNonIsaOnly =  new HashSet<>(getConceptsHavingNonIsARelationship());
			conceptsHavingNonIsaOnly.removeAll(getConceptsHavingIsARelationship());
			
			for (Long concept : conceptsHavingNonIsaOnly) {
				boolean grouped = isGrouped(concept);
				if (!grouped) {
					toAddIsAOnly.add(concept);
				} else {
					requiringIntModel.add(concept);
				}
			}
		}

		private boolean isGrouped(Long concept) {
			boolean grouped = false;
			for (String roleGroup : getNonIsARelationshipGroupMap().get(concept)) {
				if (!"0".equals(roleGroup)) {
					grouped = true;
					break;
				}
			}
			return grouped;
		}

		public ExtensionOverridingInternationalProcessor(ZipOutputStream zipOutputStream) throws IOException {
			writer = new BufferedWriter(new OutputStreamWriter(zipOutputStream));
			writer.write(RF2Headers.RELATIONSHIP_HEADER);
			writer.newLine();
		}
		
		@Override
		public void newConceptState(String conceptId, String effectiveTime, String active, String moduleId, String definitionStatusId) {
			if ("1".equals(active)) {
				if ("900000000000012004".equals(moduleId) || "900000000000207008".equals(moduleId)) {
					activeInternationalConcepts.add(Long.valueOf(conceptId));
				} else {
					if (extensionModuleId == null) {
						extensionModuleId = moduleId;
					}
				}
			}
		}
		
		@Override
		public void newRelationshipState(String id, String effectiveTime, String active, String moduleId, String sourceId, String destinationId, String relationshipGroup, String typeId, String characteristicTypeId, String modifierId) {
			if (active.equals("1") && characteristicTypeId.equals(Concepts.STATED_RELATIONSHIP)) {
				if (extensionModuleId.equals(moduleId)) {
					if (activeInternationalConcepts.contains(Long.valueOf(sourceId))) {
						if (activeInternationalConcepts.contains(Long.valueOf(destinationId))) {
							sourceAndDestinatinConcepts.add(Long.valueOf(sourceId));
							totalSourceAndDestinationAreIntConcepts++;
							if (typeId.equals(IS_A)) {
								hasIsAConcepts.add(Long.valueOf(sourceId));
							} else {
								hasNonIsAConcepts.add(Long.valueOf(sourceId));
								nonIsaRelationGroupMap.computeIfAbsent(Long.valueOf(sourceId), k -> new HashSet<String>()).add(relationshipGroup);
							}
							try {
								if (writer != null) {
									writeStateRelationshipRow(writer, id, "0", moduleId, sourceId, destinationId, relationshipGroup, typeId);
								}
							} catch (IOException e) {
								exceptionsThrown.add(e);
							}
							
						} else {
							sourceConcepts.add(Long.valueOf(sourceId));
						}
					}
				}
			}
		}
		
		public void complete() throws IOException {
			if (writer != null) {
				writer.flush();
			}
			if (!exceptionsThrown.isEmpty()) {
				throw exceptionsThrown.get(0);
			}
			analyze();
		}
		
		public Set<Long> getInternationalConceptsModifiedByExtension() {
			return this.sourceConcepts;
		}

		public String getExtensionModuleId() {
			return this.extensionModuleId;
		}
		
		public int getTotalSourceAndDestinationAreIntConcepts() {
			return this.totalSourceAndDestinationAreIntConcepts;
		}
		
		public Set<Long> getBothConceptsAreInternational() {
			return this.sourceAndDestinatinConcepts;
		}
		
		public Set<Long> getConceptsHavingIsARelationship() {
			return this.hasIsAConcepts;
		}
		
		public Set<Long> getConceptsHavingNonIsARelationship() {
			return this.hasNonIsAConcepts;
		}
		
		public Map<Long, Set<String>> getNonIsARelationshipGroupMap() {
			return this.nonIsaRelationGroupMap;
		}
	}
}

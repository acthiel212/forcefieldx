// ******************************************************************************
//
// Title:       Force Field X.
// Description: Force Field X - Software for Molecular Biophysics.
// Copyright:   Copyright (c) Michael J. Schnieders 2001-2025.
//
// This file is part of Force Field X.
//
// Force Field X is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License version 3 as published by
// the Free Software Foundation.
//
// Force Field X is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
// FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
// details.
//
// You should have received a copy of the GNU General Public License along with
// Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
// Place, Suite 330, Boston, MA 02111-1307 USA
//
// Linking this library statically or dynamically with other modules is making a
// combined work based on this library. Thus, the terms and conditions of the
// GNU General Public License cover the whole combination.
//
// As a special exception, the copyright holders of this library give you
// permission to link this library with independent modules to produce an
// executable, regardless of the license terms of these independent modules, and
// to copy and distribute the resulting executable under terms of your choice,
// provided that you also meet, for each linked independent module, the terms
// and conditions of the license of that module. An independent module is a
// module which is not derived from or based on this library. If you modify this
// library, you may extend this exception to your version of the library, but
// you are not obligated to do so. If you do not wish to do so, delete this
// exception statement from your version.
//
// ******************************************************************************

package ffx.potential.extended;

import edu.rit.pj.reduction.SharedDouble;
import ffx.numerics.Constraint;
import ffx.numerics.Potential;
import ffx.potential.ForceFieldEnergy;
import ffx.potential.MolecularAssembly;
import ffx.potential.SystemState;
import ffx.potential.bonded.AminoAcidUtils;
import ffx.potential.bonded.AminoAcidUtils.AminoAcid3;
import ffx.potential.bonded.Atom;
import ffx.potential.bonded.Residue;
import ffx.potential.constraint.ShakeChargeConstraint;
import ffx.potential.parameters.ForceField;
import ffx.potential.parameters.PolarizeType;
import ffx.potential.parameters.TitrationUtils;
import ffx.potential.parsers.ESVFilter;
import ffx.utilities.Constants;
import ffx.utilities.FileUtils;
import org.apache.commons.configuration2.CompositeConfiguration;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static ffx.potential.bonded.BondedUtils.hasAttachedAtom;
import static ffx.utilities.Constants.kB;
import static java.lang.String.format;
import static org.apache.commons.math3.util.FastMath.*;

/**
 * ExtendedSystem class.
 *
 * @author Andrew Thiel
 * @since 1.0
 */
public class ExtendedSystem implements Potential {

    private static final double DISCR_BIAS = 1.0; // kcal/mol
    private static final double LOG10 = log(10.0);
    private static final double THETA_FRICTION = 0.5; // psec^-1
    private static final double THETA_MASS = 5.0; //Atomic Mass Units
    private static final int dDiscr_dTautIndex = 6;
    private static final int dDiscr_dTitrIndex = 3;
    private static final int dModel_dTautIndex = 8;
    private static final int dModel_dTitrIndex = 5;
    private static final int dPh_dTautIndex = 7;
    private static final int dPh_dTitrIndex = 4;
    private static final int discrBiasIndex = 0;
    private static final Logger logger = Logger.getLogger(ExtendedSystem.class.getName());
    private static final int modelBiasIndex = 2;
    private static final int pHBiasIndex = 1;
    public final boolean guessTitrState;
    /**
     * Array of AminoAcid3 initialized  to match the number of atoms in the system.
     * Used to know how to apply vdW or electrostatic ESV terms for the atom.
     */
    public final AminoAcid3[] residueNames;
    /**
     * Array of ints that is initialized to match the number of atoms in the molecular assembly.
     * 1 indicates that the tautomer lambda direction is normal.
     * -1 indicates that the tautomer lambda direction is reversed (1-x).
     * 0 indicates that the atom is not a tautomerizing atom.
     */
    public final int[] tautomerDirections;
    private final double[] ASH1toASH2 = new double[4];
    /**
     * Coefficients that define the per residue type Fmod polynomial
     * quadratic * titrationLambda^2 + linear * titrationLambda
     */
    private final double[] ASHFmod = new double[4];
    private final double ASHrestraintConstant;
    /**
     * Descritizer Bias Magnitude. Default is 1 kcal/mol.
     */
    private final double ASHtautBiasMag;
    private final double ASHtitrBiasMag;
    private final double[] CYSFmod = new double[4];
    private final double CYSrestraintConstant;
    /**
     * Descritizer Bias Magnitude. Default is 1 kcal/mol.
     */
    private final double CYStitrBiasMag;
    private final double[] GLH1toGLH2 = new double[4];
    private final double[] GLHFmod = new double[4];
    private final double GLHrestraintConstant;
    /**
     * Descritizer Bias Magnitude. Default is 1 kcal/mol.
     */
    private final double GLHtautBiasMag;
    private final double GLHtitrBiasMag;
    /**
     * Coefficients that define the tautomer component of the bivariate Histidine Fmod
     * quadratic * tautomerLambda^2 + linear * tautomerLambda
     */
    private final double[] HIDFmod = new double[4];
    private final double[] HIDtoHIEFmod = new double[4];
    private final double[] HIEFmod = new double[4];
    private final double HISrestraintConstant;
    /**
     * Descritizer Bias Magnitude. Default is 1 kcal/mol.
     */
    private final double HIStautBiasMag;
    private final double HIStitrBiasMag;
    private final double[] LYSFmod = new double[4];
    private final double LYSrestraintConstant;
    /**
     * Descritizer Bias Magnitude. Default is 1 kcal/mol.
     */
    private final double LYStitrBiasMag;
    private final List<Constraint> constraints;
    public final boolean useTotalChargeCorrection;
    private final boolean doBias;
    private final boolean doElectrostatics;
    private final boolean doPolarization;
    // Controls for turning of certain terms for testing.
    private final boolean doVDW;
    /**
     * 3D array to store the titration and tautomer population states for each ESV
     */
    final private int[][][] esvHistogram;
    private final SharedDouble[] esvIndElecDerivs;
    private final SharedDouble[] esvPermElecDerivs;
    /**
     * Array of ints that is initialized to match the number of atoms in the molecular assembly.
     * Elements correspond to residue index in the tautomerizingResidueList. Only set for tautomerizing residues, -1 otherwise.
     */
    private final SystemState esvState;
    /**
     * Shared double that is initialized to match the number of ESVs in the system.
     * Once reduced, will equal either dU_Titr/dLambda or dU_Taut/dLambda for specific ESV
     */
    private final SharedDouble[] esvVdwDerivs;
    /**
     * Extended Atoms
     */
    private final Atom[] extendedAtoms;
    /**
     * Array of lambda values that matches residues in extendedResidueList
     */
    private final double[] extendedLambdas;
    /**
     * Extended Molecules
     */
    private final int[] extendedMolecules;
    /**
     * Concatenated list of titrating residues + tautomerizing residues.
     */
    private final List<Residue> extendedResidueList;
    /**
     * ForceField Energy Instance. This instance is only used for Potential implementations for grabbing the energy components.
     */
    private final ForceFieldEnergy forceFieldEnergy;
    /**
     * Array of booleans that is initialized to match the number of atoms in the molecular assembly
     * noting whether the atom is tautomerizing. Note that any side chain atom that belongs to a tautomerizing residue
     * will be flagged as tautomerizing for purposes of scaling electrostatic parameters.
     */
    private final boolean[] isTautomerizing;
    /**
     * Array of booleans that is initialized to match the number of atoms in the molecular assembly
     * noting whether the atom is titrating. Note that any side chain atom that belongs to a titrating residue
     * will be flagged as titrating for purposes of scaling electrostatic parameters.
     */
    private final boolean[] isTitrating;
    /**
     * Array of booleans that is initialized to match the number of atoms in the assembly to note whether an atom is
     * specifically a heavy atom with changing polarizability (CYS SG, ASP OD1/OD2, GLU OE1/OE2).
     */
    private final boolean[] isTitratingHeavy;
    /**
     * Array of booleans that is initialized to match the number of atoms in the assembly to note whether an atom is
     * specifically a titrating hydrogen.
     */
    private final boolean[] isTitratingHydrogen;
    /**
     * Boolean similar to fixTitrationState/fixTautomerState but is even more restrictive in that set methods
     * are not allowed to change lambda values from their initialized values.
     * Mainly used when evaluating archive snapshots at different initialized lambda values.
     * If not set to true the archive and esv files set the lambdas automatically.
     */
    private final boolean lockStates;
    /**
     * Number of atoms in the molecular assembly. Since all protons are instantiated at start, this int will not change.
     */
    private final int nAtoms;
    /**
     * Number of ESVs attached to the molecular assembly. This number is the sum of tautomerizing + titrating ESVs.
     */
    private final int nESVs;
    /**
     * Number of titrating ESVs attached to the molecular assembly.
     */
    private final int nTitr;
    /**
     * Special Residues may be used to store a list of residues for which should be initialized at a different value from their model value.
     * Special Residue pKas is the corresponding list of pKas that the selected residues should be initialized around.
     */
    private final ArrayList<Double> specialResiduePKAs;
    private final ArrayList<Double> specialResidues;
    /**
     * Select residues for Extended System to be built for. Any normally titratable residue not included in this list will not titrate.
     */
    private final ArrayList<Double> selectResidues;
    private final int[] tautomerIndexMap;
    /**
     * Array of doubles that is initialized to match the number of atoms in the molecular assembly.
     * Only elements that match a tautomerizing atom will have their lambda updated.
     */
    private final double[] tautomerLambdas;
    /**
     * List of tautomerizing residues.
     */
    private final List<Residue> tautomerizingResidueList;
    /**
     * Friction for the ESV system
     */
    private final double thetaFriction;
    /**
     * The system defined theta mass of the fictional particle. Used to fill theta mass array.
     */
    private final double thetaMass;
    /**
     * List of titrating residues.
     */
    private final List<Residue> titratingResidueList;
    /**
     * Array of ints that is initialized to match the number of atoms in the molecular assembly.
     * Elements correspond to residue index in the titratingResidueList. Only set for titrating residues, -1 otherwise.
     */
    private final int[] titrationIndexMap;
    /**
     * Array of doubles that is initialized to match the number of atoms in the molecular assembly.
     * Only elements that match a titrating atom will have their lambda updated.
     */
    private final double[] titrationLambdas;
    /**
     * Titration Utils instance. This instance is the master copy that will be distributed to electrostatics classes
     * when an Extended System is attached.
     */
    private final TitrationUtils titrationUtils;
    private final boolean useChargeConstraint;
    /**
     * Dynamics restart file.
     */
    File restartFile;
    /**
     * Boolean to keep the lambdas from updating over the course of dynamics. Useful for running dynamics
     * with extended system variables at fixed windows (i.e. BAR)
     */
    private boolean fixTautomerState;
    private boolean fixTitrationState;
    /**
     * Filter to parse the dynamics restart file.
     */
    private ESVFilter esvFilter = null;
    /**
     * System PH.
     */
    private double constantSystemPh = 7.4;
    /**
     * Target system temperature.
     */
    private double currentTemperature = Constants.ROOM_TEMPERATURE;

    /**
     * Construct extended system with the provided configuration.
     *
     * @param mola a {@link MolecularAssembly} object.
     */
    public ExtendedSystem(MolecularAssembly mola, double pH, final File esvFile) {
        extendedAtoms = mola.getAtomArray();
        extendedMolecules = mola.getMoleculeNumbers();
        setConstantPh(pH);
        ForceField forceField = mola.getForceField();
        forceFieldEnergy = mola.getPotentialEnergy();
        if (forceFieldEnergy == null) {
            logger.severe("No potential energy found?");
        }
        CompositeConfiguration properties = mola.getProperties();
        titrationUtils = new TitrationUtils(forceField);

        doVDW = properties.getBoolean("esv.vdW", true);
        doElectrostatics = properties.getBoolean("esv.elec", true);
        doBias = properties.getBoolean("esv.bias", true);
        doPolarization = properties.getBoolean("esv.polarization", true);
        useTotalChargeCorrection = properties.getBoolean("esv.total.charge.correction", false);
        thetaFriction = properties.getDouble("esv.friction", ExtendedSystem.THETA_FRICTION);
        thetaMass = properties.getDouble("esv.mass", ExtendedSystem.THETA_MASS);

        lockStates = properties.getBoolean("lock.esv.states", false); // Prevents setTitrationLambda/setTautomerLambda
        double initialTitrationLambda = properties.getDouble("initial.titration.lambda", 0.5);
        double initialTautomerLambda = properties.getDouble("initial.tautomer.lambda", 0.5);
        guessTitrState = properties.getBoolean("guess.titration.state", false);
        specialResidues = getPropertyList(properties, "esv.special.residues");
        specialResiduePKAs = getPropertyList(properties, "esv.special.residues.pka");
        selectResidues = getPropertyList(properties, "esv.select.residues");
        initSpecialResidues(specialResidues, specialResiduePKAs, mola);

        fixTitrationState = properties.getBoolean("fix.titration.lambda", false);
        fixTautomerState = properties.getBoolean("fix.tautomer.lambda", false);
        useChargeConstraint = properties.getBoolean("esv.charge.constraint", false);
        int totalCharge = properties.getInt("esv.charge.constraint.value", 0);


        ASHFmod[3] = properties.getDouble("ASH.cubic", TitrationUtils.Titration.ASHtoASP.cubic);
        ASHFmod[2] = properties.getDouble("ASH.quadratic", TitrationUtils.Titration.ASHtoASP.quadratic);
        ASHFmod[1] = properties.getDouble("ASH.linear", TitrationUtils.Titration.ASHtoASP.linear);
        ASHFmod[0] = TitrationUtils.Titration.ASHtoASP.offset;
        ASH1toASH2[3] = properties.getDouble("ASH1toASH2.cubic", TitrationUtils.Titration.ASH1toASH2.cubic);
        ASH1toASH2[2] = properties.getDouble("ASH1toASH2.quadratic", TitrationUtils.Titration.ASH1toASH2.quadratic);
        ASH1toASH2[1] = properties.getDouble("ASH1toASH2.linear", TitrationUtils.Titration.ASH1toASH2.linear);
        ASH1toASH2[0] = TitrationUtils.Titration.ASH1toASH2.offset;
        ASHtitrBiasMag = properties.getDouble("ASH.titration.bias.magnitude", DISCR_BIAS);
        ASHtautBiasMag = properties.getDouble("ASH.tautomer.bias.magnitude", DISCR_BIAS);
        ASHrestraintConstant = properties.getDouble("ASH.restraint.constant", 0.0);

        GLHFmod[3] = properties.getDouble("GLH.cubic", TitrationUtils.Titration.GLHtoGLU.cubic);
        GLHFmod[2] = properties.getDouble("GLH.quadratic", TitrationUtils.Titration.GLHtoGLU.quadratic);
        GLHFmod[1] = properties.getDouble("GLH.linear", TitrationUtils.Titration.GLHtoGLU.linear);
        GLHFmod[0] = TitrationUtils.Titration.GLHtoGLU.offset;
        GLH1toGLH2[3] = properties.getDouble("GLH1toGLH2.cubic", TitrationUtils.Titration.GLH1toGLH2.cubic);
        GLH1toGLH2[2] = properties.getDouble("GLH1toGLH2.quadratic", TitrationUtils.Titration.GLH1toGLH2.quadratic);
        GLH1toGLH2[1] = properties.getDouble("GLH1toGLH2.linear", TitrationUtils.Titration.GLH1toGLH2.linear);
        GLH1toGLH2[0] = TitrationUtils.Titration.GLH1toGLH2.offset;
        GLHtitrBiasMag = properties.getDouble("GLH.titration.bias.magnitude", DISCR_BIAS);
        GLHtautBiasMag = properties.getDouble("GLH.tautomer.bias.magnitude", DISCR_BIAS);
        GLHrestraintConstant = properties.getDouble("GLH.restraint.constant", 0.0);

        LYSFmod[3] = properties.getDouble("LYS.cubic", TitrationUtils.Titration.LYStoLYD.cubic);
        LYSFmod[2] = properties.getDouble("LYS.quadratic", TitrationUtils.Titration.LYStoLYD.quadratic);
        LYSFmod[1] = properties.getDouble("LYS.linear", TitrationUtils.Titration.LYStoLYD.linear);
        LYSFmod[0] = TitrationUtils.Titration.LYStoLYD.offset;
        LYStitrBiasMag = properties.getDouble("LYS.titration.bias.magnitude", DISCR_BIAS);
        LYSrestraintConstant = properties.getDouble("LYS.restraint.constant", 0.0);

        CYSFmod[3] = properties.getDouble("CYS.cubic", TitrationUtils.Titration.CYStoCYD.cubic);
        CYSFmod[2] = properties.getDouble("CYS.quadratic", TitrationUtils.Titration.CYStoCYD.quadratic);
        CYSFmod[1] = properties.getDouble("CYS.linear", TitrationUtils.Titration.CYStoCYD.linear);
        CYSFmod[0] = TitrationUtils.Titration.CYStoCYD.offset;
        CYStitrBiasMag = properties.getDouble("CYS.titration.bias.magnitude", DISCR_BIAS);
        CYSrestraintConstant = properties.getDouble("CYS.restraint.constant", 0.0);

        HIDFmod[3] = properties.getDouble("HID.cubic", TitrationUtils.Titration.HIStoHID.cubic);
        HIDFmod[2] = properties.getDouble("HID.quadratic", TitrationUtils.Titration.HIStoHID.quadratic);
        HIDFmod[1] = properties.getDouble("HID.linear", TitrationUtils.Titration.HIStoHID.linear);
        HIDFmod[0] = TitrationUtils.Titration.HIStoHID.offset;
        HIEFmod[3] = properties.getDouble("HIE.cubic", TitrationUtils.Titration.HIStoHIE.cubic);
        HIEFmod[2] = properties.getDouble("HIE.quadratic", TitrationUtils.Titration.HIStoHIE.quadratic);
        HIEFmod[1] = properties.getDouble("HIE.linear", TitrationUtils.Titration.HIStoHIE.linear);
        HIEFmod[0] = TitrationUtils.Titration.HIStoHIE.offset;
        HIDtoHIEFmod[3] = properties.getDouble("HIDtoHIE.cubic", TitrationUtils.Titration.HIDtoHIE.cubic);
        HIDtoHIEFmod[2] = properties.getDouble("HIDtoHIE.quadratic", TitrationUtils.Titration.HIDtoHIE.quadratic);
        HIDtoHIEFmod[1] = properties.getDouble("HIDtoHIE.linear", TitrationUtils.Titration.HIDtoHIE.linear);
        HIDtoHIEFmod[0] = TitrationUtils.Titration.HIDtoHIE.offset;
        HIStitrBiasMag = properties.getDouble("HIS.titration.bias.magnitude", DISCR_BIAS);
        HIStautBiasMag = properties.getDouble("HIS.tautomer.bias.magnitude", DISCR_BIAS);
        HISrestraintConstant = properties.getDouble("HIS.restraint.constant", 0.0);

        // Log all of the titration bias magnitudes for each titratable residue.
        logger.info("\n Titration bias magnitudes:");
        logger.info(" Lysine titration bias magnitude: " + LYStitrBiasMag);
        logger.info(" Cysteine titration bias magnitude: " + CYStitrBiasMag);
        logger.info(" Histidine titration bias magnitude: " + HIStitrBiasMag);
        logger.info(" Glutamic acid titration bias magnitude: " + GLHtitrBiasMag);
        logger.info(" Aspartic acid titration bias magnitude: " + ASHtitrBiasMag);

        // Log all of the tautomer bias magnitudes for each tautomerizable residue.
        logger.info("\n Tautomer bias magnitudes:");
        logger.info(" Histidine tautomer bias magnitude: " + HIStautBiasMag);
        logger.info(" Glutamic acid tautomer bias magnitude: " + GLHtautBiasMag);
        logger.info(" Aspartic acid tautomer bias magnitude: " + ASHtautBiasMag);

        // Log all of  th cubic, quadratic, and linear terms for all of the amino acid terms
        logger.info("\n Titration model bias (Umod) terms:");
        logger.info(" Lysine cubic term: " + LYSFmod[3]);
        logger.info(" Lysine quadratic term: " + LYSFmod[2]);
        logger.info(" Lysine linear term: " + LYSFmod[1]);
        logger.info(" Cysteine cubic term: " + CYSFmod[3]);
        logger.info(" Cysteine quadratic term: " + CYSFmod[2]);
        logger.info(" Cysteine linear term: " + CYSFmod[1]);
        logger.info(" Histidine cubic term: " + HIDFmod[3]);
        logger.info(" HID quadratic term: " + HIDFmod[2]);
        logger.info(" HID linear term: " + HIDFmod[1]);
        logger.info(" HIE cubic term: " + HIEFmod[3]);
        logger.info(" HIE quadratic term: " + HIEFmod[2]);
        logger.info(" HIE linear term: " + HIEFmod[1]);
        logger.info(" HID-HIE cubic term: " + HIDtoHIEFmod[3]);
        logger.info(" HID-HIE quadratic term: " + HIDtoHIEFmod[2]);
        logger.info(" HID-HIE linear term: " + HIDtoHIEFmod[1]);
        logger.info(" Glutamic acid cubic term: " + GLHFmod[3]);
        logger.info(" Glutamic acid quadratic term: " + GLHFmod[2]);
        logger.info(" Glutamic acid linear term: " + GLHFmod[1]);
        logger.info(" Aspartic acid cubic term: " + ASHFmod[3]);
        logger.info(" Aspartic acid quadratic term: " + ASHFmod[2]);
        logger.info(" Aspartic acid linear term: " + ASHFmod[1] + "\n");

        titratingResidueList = new ArrayList<>();
        tautomerizingResidueList = new ArrayList<>();
        extendedResidueList = new ArrayList<>();
        // Initialize atom arrays with the existing assembly.
        Atom[] atoms = mola.getAtomArray();
        nAtoms = atoms.length;
        isTitrating = new boolean[nAtoms];
        isTitratingHydrogen = new boolean[nAtoms];
        isTitratingHeavy = new boolean[nAtoms];
        isTautomerizing = new boolean[nAtoms];
        titrationLambdas = new double[nAtoms];
        tautomerLambdas = new double[nAtoms];
        titrationIndexMap = new int[nAtoms];
        tautomerIndexMap = new int[nAtoms];
        tautomerDirections = new int[nAtoms];
        residueNames = new AminoAcid3[nAtoms];

        Arrays.fill(isTitrating, false);
        Arrays.fill(isTitratingHydrogen, false);
        Arrays.fill(isTitratingHeavy, false);
        Arrays.fill(isTautomerizing, false);
        Arrays.fill(titrationLambdas, 1.0);
        Arrays.fill(tautomerLambdas, 0.0);
        Arrays.fill(titrationIndexMap, -1);
        Arrays.fill(tautomerIndexMap, -1);
        Arrays.fill(tautomerDirections, 0);
        Arrays.fill(residueNames, AminoAcid3.UNK);

        // Cycle through each residue to determine if it is titratable or tautomerizing.
        // If a residue is one of these, add to titration or tautomer lists.
        // Next, loop through all atoms and check to see if the atom belongs to this residue.
        // If the atom does belong to this residue, set all corresponding variables in the respective titration or tautomer array (size = numAtoms).
        // Store the index of the residue in the respective list into a map array (size = numAtoms).
        List<Residue> residueList = mola.getResidueList();
        List<Residue> preprocessList = new ArrayList<>(residueList);
        for (Residue residue : preprocessList) {
            List<Atom> atomList = residue.getSideChainAtoms();
            for (Atom atom : atomList) {
                //Detect disulfide sulfurs, so we can exclude these when setting up titrating residues.
                if (atom.getAtomicNumber() == 16) {
                    if (hasAttachedAtom(atom, 16)) {
                        residueList.remove(residue);
                    }
                }
            }
        }
        // Use only a list that contains AminoAcid residues so remove Nucleic Acid residues
        residueList.removeIf(residue -> (residue.getResidueType() == Residue.ResidueType.NA));
        for (Residue residue : residueList) {
            if (isTitratable(residue)) {
                if(selectResidues.isEmpty() ||  selectResidues.contains((double) residue.getResidueNumber())){
                    titratingResidueList.add(residue);
                    List<Atom> atomList = residue.getSideChainAtoms();
                    for (Atom atom : atomList) {
                        int atomIndex = atom.getArrayIndex();
                        residueNames[atomIndex] = residue.getAminoAcid3();
                        isTitrating[atomIndex] = true;
                        titrationLambdas[atomIndex] = initialTitrationState(residue, initialTitrationLambda, guessTitrState);
                        int titrationIndex = titratingResidueList.indexOf(residue);
                        titrationIndexMap[atomIndex] = titrationIndex;
                        isTitratingHydrogen[atomIndex] = TitrationUtils.isTitratingHydrogen(residue.getAminoAcid3(), atom);
                        isTitratingHeavy[atomIndex] = TitrationUtils.isTitratingHeavy(residue.getAminoAcid3(), atom);
                        // Average out pdamp values of the atoms with changing polarizability which will then be used as fixed values throughout simulation.
                        // When testing end state energies don't average pdamp.
                        // Default pdamp is set from protonated polarizability, so it must be changed when testing deprotonated end state (Titration lambda = 0.0.)
                        if (isTitratingHeavy(atomIndex)) {
                            //If polarization is turned off atom.getPolarizeType() will return null
                            if (atom.getPolarizeType() != null) {
                                double deprotPolar = titrationUtils.getPolarizability(atom, 0.0, 0.0, atom.getPolarizeType().polarizability);
                                double protPolar = titrationUtils.getPolarizability(atom, 1.0, 1.0, atom.getPolarizeType().polarizability);
                                double avgPolar = 0.5 * deprotPolar + 0.5 * protPolar;
                                PolarizeType esvPolarizingHeavyAtom = new PolarizeType(atom.getType(), avgPolar, atom.getPolarizeType().thole,
                                        atom.getPolarizeType().ddp, atom.getPolarizeType().polarizationGroup);
                                atom.setPolarizeType(esvPolarizingHeavyAtom);
                            }
                        }
                    }
                    // If is a tautomer, it must also be titrating.
                    if (isTautomer(residue)) {
                        tautomerizingResidueList.add(residue);
                        for (Atom atom : atomList) {
                            int atomIndex = atom.getArrayIndex();
                            isTautomerizing[atomIndex] = true;
                            tautomerLambdas[atomIndex] = initialTautomerLambda;
                            int tautomerIndex = tautomerizingResidueList.indexOf(residue);
                            tautomerIndexMap[atomIndex] = tautomerIndex;
                            tautomerDirections[atomIndex] = TitrationUtils.getTitratingHydrogenDirection(residue.getAminoAcid3(), atom);
                        }
                    }
                    // Test the multipole frames during unit testing.
                    assert (titrationUtils.testResidueTypes(residue));
                }
            }
        }

        //Concatenate titratingResidueList and tautomerizingResidueList
        extendedResidueList.addAll(titratingResidueList);
        extendedResidueList.addAll(tautomerizingResidueList);
        //Arrays that are sent to integrator are based on extendedResidueList size
        nESVs = extendedResidueList.size();
        nTitr = titratingResidueList.size();
        extendedLambdas = new double[nESVs];
        esvState = new SystemState(nESVs);
        //thetaPosition = new double[nESVs];
        //thetaVelocity = new double[nESVs];
        //thetaAccel = new double[nESVs];
        //thetaMassArray = new double[nESVs];
        esvHistogram = new int[nTitr][10][10];
        esvVdwDerivs = new SharedDouble[nESVs];
        esvPermElecDerivs = new SharedDouble[nESVs];
        esvIndElecDerivs = new SharedDouble[nESVs];
        for (int i = 0; i < nESVs; i++) {
            esvVdwDerivs[i] = new SharedDouble(0.0);
            esvPermElecDerivs[i] = new SharedDouble(0.0);
            esvIndElecDerivs[i] = new SharedDouble(0.0);
        }
        if (useChargeConstraint) {
            constraints = new ArrayList<>();
            ShakeChargeConstraint chargeConstraint = new ShakeChargeConstraint(nTitr, totalCharge, 0.001);
            constraints.add(chargeConstraint);
        } else {
            constraints = Collections.emptyList();
        }

        //Theta masses should always be the same for each ESV
        Arrays.fill(esvState.getMass(), thetaMass);

        for (int i = 0; i < nESVs; i++) {
            if (i < nTitr) {
                Residue residue = extendedResidueList.get(i);
                double initialTitrLambda = initialTitrationState(residue, initialTitrationLambda, guessTitrState);
                initializeThetaArrays(i, initialTitrLambda);
            } else {
                initializeThetaArrays(i, initialTautomerLambda);
            }
        }
        if (esvFilter == null) {
            esvFilter = new ESVFilter(mola.getName());
        }
        if (esvFile == null) {
            String firstFileName = FilenameUtils.removeExtension(mola.getFile().getAbsolutePath());
            restartFile = new File(firstFileName + ".esv");
        } else {
            double[] thetaPosition = esvState.x();
            double[] thetaVelocity = esvState.v();
            double[] thetaAccel = esvState.a();
            if (!esvFilter.readESV(esvFile, thetaPosition, thetaVelocity, thetaAccel, esvHistogram)) {
                String message = " Could not load the restart file - dynamics terminated.";
                logger.log(Level.WARNING, message);
                throw new IllegalStateException(message);
            } else {
                restartFile = esvFile;
                updateLambdas();
            }
        }
        logger.info("\n Extended System created for the following residues: " + titratingResidueList);
    }

    /**
     * Initialize special residues specified in the key file
     */
    private void initSpecialResidues(ArrayList<Double> specialResidues, ArrayList<Double> specialResiduePKAs, MolecularAssembly mola) {
        if (specialResidues.size() != specialResiduePKAs.size() && !specialResiduePKAs.isEmpty()) {
            logger.severe("The number of special residues and their associated values do not match.");
        } else if (!specialResidues.isEmpty()) {
            logger.info("\n Special residues and their associated values:");
            for (int i = 0; i < specialResidues.size(); i++) {
                int resNum = (int) (double) specialResidues.get(i) - mola.getResidueList().get(0).getResidueNumber(); // Shift pdb index by first residue number
                if (!specialResiduePKAs.isEmpty()) {
                    logger.info(" Residue: " + specialResidues.get(i) + "-" +
                            mola.getResidueList().get(resNum).getName()
                            + " Pka: " + specialResiduePKAs.get(i));
                }
            }
            logger.info(" ");
        }
        logger.info(" Special residues: " + specialResidues);
        logger.info(" Special residues pKa: " + specialResiduePKAs);
        for (Residue res : mola.getResidueList()) {
            if (!isTitratable(res) && specialResidues.contains((double) res.getResidueNumber())) {
                logger.severe("Given special residue: " + res + " is not titratable.");
            }
        }
    }

    /**
     * Returns the titratibility of the passed residue
     */
    public boolean isTitratable(Residue residue) {
        if (residue.getResidueType() == Residue.ResidueType.NA) {
            return false;
        }
        AminoAcidUtils.AminoAcid3 AA3 = residue.getAminoAcid3();
        return AA3.isConstantPhTitratable;
    }

    // Method that takes in properties, a string.
    private ArrayList<Double> getPropertyList(CompositeConfiguration properties, String s) {
        ArrayList<Double> list = new ArrayList<>();
        String[] split = properties.getString(s, "").trim()
                .replace("[", "")
                .replace("]", "")
                .replace(",", " ")
                .split(" ");
        for (String s1 : split) {
            if (s1.isEmpty()) {
                continue;
            }
            list.add(Double.parseDouble(s1));
        }
        return list;
    }

    /**
     * During constructor, initialize arrays that will hold theta positions, velocities, and accelerations.
     * Positions determined from starting lambda.
     * Velocities randomly set according to Maxwell Boltzmann Distribution based on temperature.
     * Accelerations determined from initial forces.
     *
     * @param index  index of ExtendedResidueList for which to set these values.
     * @param lambda starting lambda value for each ESV.
     */
    private void initializeThetaArrays(int index, double lambda) {
        extendedLambdas[index] = lambda;
        double[] thetaPosition = esvState.x();
        double[] thetaVelocity = esvState.v();
        double[] thetaAccel = esvState.a();
        thetaPosition[index] = Math.asin(Math.sqrt(lambda));
        //Perform unit analysis carefully
        Random random = new Random();
        thetaVelocity[index] = random.nextGaussian() * sqrt(kB * 298.15 / thetaMass);
        double dUdL = getDerivatives()[index];
        double dUdTheta = dUdL * sin(2 * thetaPosition[index]);
        thetaAccel[index] = -Constants.KCAL_TO_GRAM_ANG2_PER_PS2 * dUdTheta / thetaMass;
        //logger.info(format("Index: %d, dU/dL: %6.8f, dU/dTheta: %6.8f Theta Accel: %6.8f, Theta Velocity: %6.8f", index, -Constants.KCAL_TO_GRAM_ANG2_PER_PS2*dUdL, -Constants.KCAL_TO_GRAM_ANG2_PER_PS2*dUdTheta, thetaAccel[index], thetaVelocity[index]));
    }

    /**
     * get array of dU/dL for each titrating residue
     *
     * @return esvDeriv a double[]
     */
    public double[] getDerivatives() {
        double[] esvDeriv = new double[nESVs];
        double[] biasDerivComponents = new double[9];

        for (Residue residue : titratingResidueList) {
            int resTitrIndex = titratingResidueList.indexOf(residue);
            //Bias Terms
            if (doBias) {
                getBiasTerms(residue, biasDerivComponents);
                //Sum up titration bias derivs
                esvDeriv[resTitrIndex] += biasDerivComponents[dDiscr_dTitrIndex] + biasDerivComponents[dPh_dTitrIndex] + biasDerivComponents[dModel_dTitrIndex];
                //Sum up tautomer bias derivs
                if (isTautomer(residue)) {
                    int resTautIndex = tautomerizingResidueList.indexOf(residue) + nTitr;
                    esvDeriv[resTautIndex] += biasDerivComponents[dDiscr_dTautIndex] + biasDerivComponents[dPh_dTautIndex] + biasDerivComponents[dModel_dTautIndex];
                }
            }

            if (doVDW) {
                esvDeriv[resTitrIndex] += getVdwDeriv(resTitrIndex);
                //Sum up tautomer bias derivs
                if (isTautomer(residue)) {
                    int resTautIndex = tautomerizingResidueList.indexOf(residue) + nTitr;
                    esvDeriv[resTautIndex] += getVdwDeriv(resTautIndex);
                }
            }

            if (doElectrostatics) {
                esvDeriv[resTitrIndex] += getPermElecDeriv(resTitrIndex);
                //Sum up tautomer bias derivs
                if (isTautomer(residue)) {
                    int resTautIndex = tautomerizingResidueList.indexOf(residue) + nTitr;
                    esvDeriv[resTautIndex] += getPermElecDeriv(resTautIndex);
                }
                if (doPolarization) {
                    esvDeriv[resTitrIndex] += getIndElecDeriv(resTitrIndex);
                    if (isTautomer(residue)) {
                        int resTautIndex = tautomerizingResidueList.indexOf(residue) + nTitr;
                        esvDeriv[resTautIndex] += getIndElecDeriv(resTautIndex);
                    }
                }
            }
        }
        return esvDeriv;
    }

    /**
     * Collect respective pH, model, and discr bias terms and their derivatives for each titrating residue.
     */
    private void getBiasTerms(Residue residue, double[] biasEnergyAndDerivs) {
        AminoAcidUtils.AminoAcid3 AA3 = residue.getAminoAcid3();
        double titrationLambda = getTitrationLambda(residue);
        double titrationLambdaSquared = titrationLambda * titrationLambda;
        double titrationLambdaCubed = titrationLambdaSquared * titrationLambda;
        double discrBias;
        double pHBias;
        double modelBias;
        double dDiscr_dTitr;
        double dDiscr_dTaut;
        double dPh_dTitr;
        double dPh_dTaut;
        double dMod_dTitr;
        double dMod_dTaut;
        //If bias terms shouldn't be computed, set AA3 to UNK so that default case executes and all terms are set to zero.
        if (!doBias) {
            AA3 = AminoAcid3.UNK;
        }
        switch (AA3) {
            case ASD:
            case ASH:
            case ASP:
                // Discr Bias & Derivs
                double tautomerLambda = getTautomerLambda(residue);
                discrBias = -4.0 * ASHtitrBiasMag * (titrationLambda - 0.5) * (titrationLambda - 0.5);
                discrBias += -4.0 * ASHtautBiasMag * (tautomerLambda - 0.5) * (tautomerLambda - 0.5);
                dDiscr_dTitr = -8.0 * ASHtitrBiasMag * (titrationLambda - 0.5);
                dDiscr_dTaut = -8.0 * ASHtautBiasMag * (tautomerLambda - 0.5);

                // pH Bias & Derivs
                double pKa1 = TitrationUtils.Titration.ASHtoASP.pKa;
                double pKa2 = pKa1;
                pHBias = LOG10 * Constants.R * currentTemperature * (1.0 - titrationLambda)
                        * (tautomerLambda * (pKa1 - constantSystemPh) + (1.0 - tautomerLambda) * (pKa2 - constantSystemPh));
                dPh_dTitr = LOG10 * Constants.R * currentTemperature * -1.0
                        * (tautomerLambda * (pKa1 - constantSystemPh) + (1.0 - tautomerLambda) * (pKa2 - constantSystemPh));
                dPh_dTaut = LOG10 * Constants.R * currentTemperature * (1.0 - titrationLambda)
                        * ((pKa1 - constantSystemPh) - (pKa2 - constantSystemPh));

                // pH restraint to add to pH Bias
                double restraint = ASHrestraintConstant;
                double pHSignedSquared = (pKa1 - constantSystemPh) * Math.abs(pKa1 - constantSystemPh);
                // x*abs(x) is quadratic-like but sign is preserved
                pHBias += restraint * (1.0 - titrationLambda) * pHSignedSquared;
                dPh_dTitr += restraint * -1.0 * pHSignedSquared;

                // Model Bias & Derivs
                double coeffA0 = ASH1toASH2[3];
                double coeffA1 = ASH1toASH2[2];
                double coeffA2 = ASH1toASH2[1];
                double coeffB0 = ASHFmod[3];
                double coeffB1 = ASHFmod[2];
                double coeffB2 = ASHFmod[1];
                double coeffC0 = ASHFmod[3];
                double coeffC1 = ASHFmod[2];
                double coeffC2 = ASHFmod[1];
                double tautomerLambdaSquared = tautomerLambda * tautomerLambda;
                double tautomerLambdaCubed = tautomerLambdaSquared * tautomerLambda;
                double oneMinusTitrationLambda = (1.0 - titrationLambda);
                double oneMinusTautomerLambda = (1.0 - tautomerLambda);
                double coeffBSum = coeffB0 + coeffB1 + coeffB2;
                double coeffCSum = coeffC0 + coeffC1 + coeffC2;
                modelBias = titrationLambda * (coeffA0 * tautomerLambdaCubed + coeffA1 * tautomerLambdaSquared + coeffA2 * tautomerLambda) +
                        tautomerLambda * (coeffB0 * titrationLambdaCubed + coeffB1 * titrationLambdaSquared + coeffB2 * titrationLambda) +
                        oneMinusTautomerLambda * (coeffC0 * titrationLambdaCubed + coeffC1 * titrationLambdaSquared + coeffC2 * titrationLambda) +
                        //Enforce that HIS(titration==1) state is equal energy no matter tautomer value
                        oneMinusTitrationLambda * (coeffCSum - coeffBSum) * tautomerLambda;
                dMod_dTitr = (coeffA0 * tautomerLambdaCubed + coeffA1 * tautomerLambdaSquared + coeffA2 * tautomerLambda) +
                        tautomerLambda * (3.0 * coeffB0 * titrationLambdaSquared + 2.0 * coeffB1 * titrationLambda + coeffB2) +
                        oneMinusTautomerLambda * (3.0 * coeffC0 * titrationLambdaSquared + 2.0 * coeffC1 * titrationLambda + coeffC2) +
                        -tautomerLambda * (coeffCSum - coeffBSum);
                dMod_dTaut = titrationLambda * (3.0 * coeffA0 * tautomerLambdaSquared + 2.0 * coeffA1 * tautomerLambda + coeffA2) +
                        (coeffB0 * titrationLambdaCubed + coeffB1 * titrationLambdaSquared + coeffB2 * titrationLambda) +
                        -1.0 * (coeffC0 * titrationLambdaCubed + coeffC1 * titrationLambdaSquared + coeffC2 * titrationLambda) +
                        oneMinusTautomerLambda * (coeffCSum - coeffBSum);
                break;
            case GLD:
            case GLH:
            case GLU:
                // Discr Bias & Derivs
                tautomerLambda = getTautomerLambda(residue);
                discrBias = -4.0 * GLHtitrBiasMag * (titrationLambda - 0.5) * (titrationLambda - 0.5);
                discrBias += -4.0 * GLHtautBiasMag * (tautomerLambda - 0.5) * (tautomerLambda - 0.5);
                dDiscr_dTitr = -8.0 * GLHtitrBiasMag * (titrationLambda - 0.5);
                dDiscr_dTaut = -8.0 * GLHtautBiasMag * (tautomerLambda - 0.5);

                // pH Bias & Derivs
                pKa1 = TitrationUtils.Titration.GLHtoGLU.pKa;
                pKa2 = pKa1;
                pHBias = LOG10 * Constants.R * currentTemperature * (1.0 - titrationLambda)
                        * (tautomerLambda * (pKa1 - constantSystemPh) + (1.0 - tautomerLambda) * (pKa2 - constantSystemPh));
                dPh_dTitr = LOG10 * Constants.R * currentTemperature * -1.0
                        * (tautomerLambda * (pKa1 - constantSystemPh) + (1.0 - tautomerLambda) * (pKa2 - constantSystemPh));
                dPh_dTaut = LOG10 * Constants.R * currentTemperature * (1.0 - titrationLambda)
                        * ((pKa1 - constantSystemPh) - (pKa2 - constantSystemPh));

                // pH restraint to add to pH Bias
                restraint = GLHrestraintConstant;
                pHSignedSquared = (pKa1 - constantSystemPh) * Math.abs(pKa1 - constantSystemPh);
                // x*abs(x) is quadratic-like but sign is preserved
                pHBias += restraint * (1.0 - titrationLambda) * pHSignedSquared;
                dPh_dTitr += restraint * -1.0 * pHSignedSquared;

                // Model Bias & Derivs
                coeffA0 = GLH1toGLH2[3];
                coeffA1 = GLH1toGLH2[2];
                coeffA2 = GLH1toGLH2[1];
                coeffB0 = GLHFmod[3];
                coeffB1 = GLHFmod[2];
                coeffB2 = GLHFmod[1];
                coeffC0 = GLHFmod[3];
                coeffC1 = GLHFmod[2];
                coeffC2 = GLHFmod[1];
                tautomerLambdaSquared = tautomerLambda * tautomerLambda;
                tautomerLambdaCubed = tautomerLambdaSquared * tautomerLambda;
                oneMinusTitrationLambda = (1.0 - titrationLambda);
                oneMinusTautomerLambda = (1.0 - tautomerLambda);
                coeffBSum = coeffB0 + coeffB1 + coeffB2;
                coeffCSum = coeffC0 + coeffC1 + coeffC2;
                modelBias = titrationLambda * (coeffA0 * tautomerLambdaCubed + coeffA1 * tautomerLambdaSquared + coeffA2 * tautomerLambda) +
                        tautomerLambda * (coeffB0 * titrationLambdaCubed + coeffB1 * titrationLambdaSquared + coeffB2 * titrationLambda) +
                        oneMinusTautomerLambda * (coeffC0 * titrationLambdaCubed + coeffC1 * titrationLambdaSquared + coeffC2 * titrationLambda) +
                        //Enforce that HIS(titration==1) state is equal energy no matter tautomer value
                        oneMinusTitrationLambda * (coeffCSum - coeffBSum) * tautomerLambda;
                dMod_dTitr = (coeffA0 * tautomerLambdaCubed + coeffA1 * tautomerLambdaSquared + coeffA2 * tautomerLambda) +
                        tautomerLambda * (3.0 * coeffB0 * titrationLambdaSquared + 2.0 * coeffB1 * titrationLambda + coeffB2) +
                        oneMinusTautomerLambda * (3.0 * coeffC0 * titrationLambdaSquared + 2.0 * coeffC1 * titrationLambda + coeffC2) +
                        -tautomerLambda * (coeffCSum - coeffBSum);
                dMod_dTaut = titrationLambda * (3.0 * coeffA0 * tautomerLambdaSquared + 2.0 * coeffA1 * tautomerLambda + coeffA2) +
                        (coeffB0 * titrationLambdaCubed + coeffB1 * titrationLambdaSquared + coeffB2 * titrationLambda) +
                        -1.0 * (coeffC0 * titrationLambdaCubed + coeffC1 * titrationLambdaSquared + coeffC2 * titrationLambda) +
                        oneMinusTautomerLambda * (coeffCSum - coeffBSum);
                break;
            case HIS:
            case HID:
            case HIE:
                // Discr Bias & Derivs
                tautomerLambda = getTautomerLambda(residue);

                discrBias = -4.0 * HIStitrBiasMag * (titrationLambda - 0.5) * (titrationLambda - 0.5);
                discrBias += -4.0 * HIStautBiasMag * (tautomerLambda - 0.5) * (tautomerLambda - 0.5);
                dDiscr_dTitr = -8.0 * HIStitrBiasMag * (titrationLambda - 0.5);
                dDiscr_dTaut = -8.0 * HIStautBiasMag * (tautomerLambda - 0.5);

                // pH Bias & Derivs
                // At tautomerLambda=1 HIE is fully on.
                pKa1 = TitrationUtils.Titration.HIStoHIE.pKa;
                // At tautomerLambda=0 HID is fully on.
                pKa2 = TitrationUtils.Titration.HIStoHID.pKa;
                pHBias = LOG10 * Constants.R * currentTemperature * (1.0 - titrationLambda)
                        * (tautomerLambda * (pKa1 - constantSystemPh) + (1.0 - tautomerLambda) * (pKa2 - constantSystemPh));
                dPh_dTitr = LOG10 * Constants.R * currentTemperature * -1.0
                        * (tautomerLambda * (pKa1 - constantSystemPh) + (1.0 - tautomerLambda) * (pKa2 - constantSystemPh));
                dPh_dTaut = LOG10 * Constants.R * currentTemperature * (1.0 - titrationLambda)
                        * ((pKa1 - constantSystemPh) - (pKa2 - constantSystemPh));

                // pH restraint to add to pH Bias
                restraint = HISrestraintConstant;
                double pH1SignedSquared = (pKa1 - constantSystemPh) * Math.abs(pKa1 - constantSystemPh);
                double pH2SignedSquared = (pKa2 - constantSystemPh) * Math.abs(pKa2 - constantSystemPh);
                // x*abs(x) is quadratic-like but sign is preserved
                pHBias += restraint * (1.0 - titrationLambda)
                        * (tautomerLambda * pH1SignedSquared + (1.0 - tautomerLambda) * pH2SignedSquared);
                dPh_dTitr += restraint * -1.0
                        * (tautomerLambda * pH1SignedSquared + (1.0 - tautomerLambda) * pH2SignedSquared);
                dPh_dTaut += restraint * (1.0 - titrationLambda)
                        * (pH1SignedSquared - pH2SignedSquared);
                // Model Bias & Derivs

                coeffA0 = HIDtoHIEFmod[3];
                coeffA1 = HIDtoHIEFmod[2];
                coeffA2 = HIDtoHIEFmod[1];
                coeffB0 = HIEFmod[3];
                coeffB1 = HIEFmod[2];
                coeffB2 = HIEFmod[1];
                coeffC0 = HIDFmod[3];
                coeffC1 = HIDFmod[2];
                coeffC2 = HIDFmod[1];
                tautomerLambdaSquared = tautomerLambda * tautomerLambda;
                tautomerLambdaCubed = tautomerLambdaSquared * tautomerLambda;
                oneMinusTitrationLambda = (1.0 - titrationLambda);
                oneMinusTautomerLambda = (1.0 - tautomerLambda);
                coeffBSum = coeffB0 + coeffB1 + coeffB2;
                coeffCSum = coeffC0 + coeffC1 + coeffC2;
                modelBias = oneMinusTitrationLambda * (coeffA0 * tautomerLambdaCubed + coeffA1 * tautomerLambdaSquared + coeffA2 * tautomerLambda) +
                        tautomerLambda * (coeffB0 * titrationLambdaCubed + coeffB1 * titrationLambdaSquared + coeffB2 * titrationLambda) +
                        oneMinusTautomerLambda * (coeffC0 * titrationLambdaCubed + coeffC1 * titrationLambdaSquared + coeffC2 * titrationLambda) +
                        //Enforce that HIS(titration==1) state is equal energy no matter tautomer value
                        titrationLambda * (coeffCSum - coeffBSum) * tautomerLambda;
                dMod_dTitr = -(coeffA0 * tautomerLambdaCubed + coeffA1 * tautomerLambdaSquared + coeffA2 * tautomerLambda) +
                        tautomerLambda * (3.0 * coeffB0 * titrationLambdaSquared + 2.0 * coeffB1 * titrationLambda + coeffB2) +
                        oneMinusTautomerLambda * (3.0 * coeffC0 * titrationLambdaSquared + 2.0 * coeffC1 * titrationLambda + coeffC2) +
                        tautomerLambda * (coeffCSum - coeffBSum);
                dMod_dTaut = oneMinusTitrationLambda * (3.0 * coeffA0 * tautomerLambdaSquared + 2.0 * coeffA1 * tautomerLambda + coeffA2) +
                        (coeffB0 * titrationLambdaCubed + coeffB1 * titrationLambdaSquared + coeffB2 * titrationLambda) +
                        -1.0 * (coeffC0 * titrationLambdaCubed + coeffC1 * titrationLambdaSquared + coeffC2 * titrationLambda) +
                        titrationLambda * (coeffCSum - coeffBSum);

                break;
            case LYS:
            case LYD:
                // Discr Bias & Derivs
                discrBias = -4.0 * LYStitrBiasMag * (titrationLambda - 0.5) * (titrationLambda - 0.5);
                dDiscr_dTitr = -8.0 * LYStitrBiasMag * (titrationLambda - 0.5);
                dDiscr_dTaut = 0.0;

                // pH Bias & Derivs
                pKa1 = TitrationUtils.Titration.LYStoLYD.pKa;
                pHBias = LOG10 * Constants.R * currentTemperature * (1.0 - titrationLambda) * (pKa1 - constantSystemPh);
                dPh_dTitr = LOG10 * Constants.R * currentTemperature * -1.0 * (pKa1 - constantSystemPh);
                dPh_dTaut = 0.0;

                // pH restraint to add to pH Bias
                restraint = LYSrestraintConstant;
                pHSignedSquared = (pKa1 - constantSystemPh) * Math.abs(pKa1 - constantSystemPh);
                // x*abs(x) is quadratic-like but sign is preserved
                pHBias += restraint * (1.0 - titrationLambda) * pHSignedSquared;
                dPh_dTitr += restraint * -1.0 * pHSignedSquared;

                // Model Bias & Derivs
                double cubic = LYSFmod[3];
                double quadratic = LYSFmod[2];
                double linear = LYSFmod[1];
                modelBias = cubic * titrationLambdaCubed + quadratic * titrationLambdaSquared + linear * titrationLambda;
                dMod_dTitr = 3 * cubic * titrationLambdaSquared + 2 * quadratic * titrationLambda + linear;
                dMod_dTaut = 0.0;
                break;
            case CYS:
            case CYD:
                // Discr Bias & Derivs
                discrBias = -4.0 * CYStitrBiasMag * (titrationLambda - 0.5) * (titrationLambda - 0.5);
                dDiscr_dTitr = -8.0 * CYStitrBiasMag * (titrationLambda - 0.5);
                dDiscr_dTaut = 0.0;

                // pH Bias & Derivs
                pKa1 = TitrationUtils.Titration.CYStoCYD.pKa;
                pHBias = LOG10 * Constants.R * currentTemperature * (1.0 - titrationLambda) * (pKa1 - constantSystemPh);
                dPh_dTitr = LOG10 * Constants.R * currentTemperature * -1.0 * (pKa1 - constantSystemPh);
                dPh_dTaut = 0.0;

                // pH restraint to add to pH Bias
                restraint = CYSrestraintConstant;
                pHSignedSquared = (pKa1 - constantSystemPh) * Math.abs(pKa1 - constantSystemPh);
                // x*abs(x) is quadratic-like but sign is preserved
                pHBias += restraint * (1.0 - titrationLambda) * pHSignedSquared;
                dPh_dTitr += restraint * -1.0 * pHSignedSquared;


                // Model Bias & Derivs
                cubic = CYSFmod[3];
                quadratic = CYSFmod[2];
                linear = CYSFmod[1];
                modelBias = cubic * titrationLambdaCubed + quadratic * titrationLambdaSquared + linear * titrationLambda;
                dMod_dTitr = 3 * cubic * titrationLambdaSquared + 2 * quadratic * titrationLambda + linear;
                dMod_dTaut = 0.0;
                break;
            default:
                discrBias = 0.0;
                pHBias = 0.0;
                modelBias = 0.0;
                dDiscr_dTitr = 0.0;
                dDiscr_dTaut = 0.0;
                dPh_dTitr = 0.0;
                dPh_dTaut = 0.0;
                dMod_dTitr = 0.0;
                dMod_dTaut = 0.0;
                break;
        }
        biasEnergyAndDerivs[0] = discrBias;
        biasEnergyAndDerivs[1] = pHBias;
        biasEnergyAndDerivs[2] = -modelBias;
        biasEnergyAndDerivs[3] = dDiscr_dTitr;
        biasEnergyAndDerivs[4] = dPh_dTitr;
        biasEnergyAndDerivs[5] = -dMod_dTitr;
        biasEnergyAndDerivs[6] = dDiscr_dTaut;
        biasEnergyAndDerivs[7] = dPh_dTaut;
        biasEnergyAndDerivs[8] = -dMod_dTaut;
    }

    /**
     * Gets the titration lambda for the input residue if the residue is titrating
     *
     * @param residue a titrating residue
     * @return the titration lambda for the residue
     */
    public double getTitrationLambda(Residue residue) {
        if (titratingResidueList.contains(residue)) {
            int resIndex = titratingResidueList.indexOf(residue);
            return extendedLambdas[resIndex];
        } else {
            return 1.0;
        }
    }

    /**
     * Gets the tautomer lambda for the input residue if the residue is tautomerizing
     *
     * @param residue a tautomer residue
     * @return the tautomer lambda for the residue
     */
    public double getTautomerLambda(Residue residue) {
        if (tautomerizingResidueList.contains(residue)) {
            int resIndex = tautomerizingResidueList.indexOf(residue);
            return extendedLambdas[nTitr + resIndex];
        } else {
            return 1.0;
        }
    }

    /**
     * get total dUvdw/dL for the selected extended system variable
     */
    private double getVdwDeriv(int esvID) {
        return esvVdwDerivs[esvID].get();
    }

    /**
     * get total dUpermElec/dL for the selected extended system variable.
     */
    private double getPermElecDeriv(int esvID) {
        return esvPermElecDerivs[esvID].get();
    }

    /**
     * get total dUindElec/dL for the selected extended system variable
     */
    private double getIndElecDeriv(int esvID) {
        return esvIndElecDerivs[esvID].get();
    }

    /**
     * Returns the tautomerizibility of a residue
     */
    public boolean isTautomer(Residue residue) {
        if (residue.getResidueType() == Residue.ResidueType.NA) {
            return false;
        }
        AminoAcidUtils.AminoAcid3 AA3 = residue.getAminoAcid3();
        return AA3.isConstantPhTautomer;
    }

    /**
     * Update all theta (lambda) positions after each move from the Stochastic integrator
     */
    private void updateLambdas() {
        //If lockStates is true, then the titration and tautomer states are permanently locked.
        if (lockStates) {
            return;
        }
        double[] thetaPosition = esvState.x();
        //This will prevent recalculating multiple sinTheta*sinTheta that are the same number.
        for (int i = 0; i < nESVs; i++) {
            //Check to see if titration/tautomer lambdas are to be fixed
            if ((!fixTitrationState && i < nTitr) || (!fixTautomerState && i >= nTitr)) {
                double sinTheta = Math.sin(thetaPosition[i]);
                extendedLambdas[i] = sinTheta * sinTheta;
            }
        }
        for (int i = 0; i < nAtoms; i++) {
            int mappedTitrationIndex = titrationIndexMap[i];
            int mappedTautomerIndex = tautomerIndexMap[i] + nTitr;
            if (isTitrating(i) && mappedTitrationIndex != -1) {
                titrationLambdas[i] = extendedLambdas[mappedTitrationIndex];
            }
            if (isTautomerizing(i) && mappedTautomerIndex >= nTitr) {
                tautomerLambdas[i] = extendedLambdas[mappedTautomerIndex];
            }
        }
        setESVHistogram();
    }

    /**
     * Returns whether an atom is titrating
     */
    public boolean isTitrating(int atomIndex) {
        return isTitrating[atomIndex];
    }

    /**
     * Returns whether an atom is tautomerizing
     */
    public boolean isTautomerizing(int atomIndex) {
        return isTautomerizing[atomIndex];
    }

    /**
     * Goes through residues updates the ESV histogram based on the residues current state
     */
    private void setESVHistogram() {
        for (Residue residue : titratingResidueList) {
            int index = titratingResidueList.indexOf(residue);
            if (residue.getAminoAcid3().equals(AminoAcid3.LYS)) { // TODO: Add support for CYS? by adding "|| residue.getAminoAcid3().equals(AminoAcid3.CYS))"
                double titrLambda = getTitrationLambda(residue);
                esvHistogram(index, titrLambda);
            } else {
                double titrLambda = getTitrationLambda(residue);
                double tautLambda = getTautomerLambda(residue);
                esvHistogram(index, titrLambda, tautLambda);
            }
        }
    }

    /**
     * Updates the ESV histogram of the passed residue at the given lambda
     *
     * @param esv    the index of the esv to be updated
     * @param lambda the lambda value to be updated
     */
    private void esvHistogram(int esv, double lambda) {
        int value = (int) (lambda * 10.0);
        //Cover the case where lambda could be exactly 1.0
        if (value == 10) {
            value = 9;
        }
        esvHistogram[esv][value][0]++;
    }

    /**
     * Updates the ESV histogram of the passed residue at the given titr and taut state
     *
     * @param esv        the index of the esv to be updated
     * @param titrLambda the titration lambda coordinate to be updated
     * @param tautLambda the tautomer lambda coordinate to be updated
     */
    private void esvHistogram(int esv, double titrLambda, double tautLambda) {
        int titrValue = (int) (titrLambda * 10.0);
        //Cover the case where lambda could be exactly 1.0
        if (titrValue == 10) {
            titrValue = 9;
        }
        int tautValue = (int) (tautLambda * 10.0);
        if (tautValue == 10) {
            tautValue = 9;
        }
        esvHistogram[esv][titrValue][tautValue]++;
    }

    /**
     * Naive guess as to what the best starting state should be based purely on the acidostat term.
     */
    private double initialTitrationState(Residue residue, double initialLambda, boolean guessTitrState) {
        AminoAcid3 AA3 = residue.getAminoAcid3();
        double residueNumber = residue.getResidueNumber();
        double initialTitrationLambda = 0.0;
        if (specialResidues.contains(residueNumber)) { // If we set a special residue for this state
            if (!specialResiduePKAs.isEmpty()) {
                initialTitrationLambda =
                        (constantSystemPh < specialResiduePKAs.get(specialResidues.indexOf(residueNumber))) ? 1.0 : 0.0;
            }
        } else if (!guessTitrState) { // If we do not want to guess the titration state but instead use the value
            // passed in via the initialLambda parameter.
            initialTitrationLambda = initialLambda;
        } else { // Guess titration state
            initialTitrationLambda = switch (AA3) {
                case ASD -> (constantSystemPh < TitrationUtils.Titration.ASHtoASP.pKa) ? 1.0 : 0.0;
                case GLD -> (constantSystemPh < TitrationUtils.Titration.GLHtoGLU.pKa) ? 1.0 : 0.0;
                case HIS -> (constantSystemPh < TitrationUtils.Titration.HIStoHID.pKa) ? 1.0 : 0.0;
                case LYS -> (constantSystemPh < TitrationUtils.Titration.LYStoLYD.pKa) ? 1.0 : 0.0;
                case CYS -> (constantSystemPh < TitrationUtils.Titration.CYStoCYD.pKa) ? 1.0 : 0.0;
                default -> initialLambda;
            };
        }
        return initialTitrationLambda;
    }

    /**
     * Questions whether the current non-hydrogen atom's polarizability is changing in response to lambda being updated.
     * Only affects carboxylic oxygen and sulfur.
     *
     * @param atomIndex
     * @return
     */
    public boolean isTitratingHeavy(int atomIndex) {
        return isTitratingHeavy[atomIndex];
    }

    // Getter for specialResidues
    public ArrayList<Double> getSpecialResidueList() {
        return specialResidues;
    }

    /**
     * Does not allow for changes to the tautomer states of tautomerizing residues
     */
    public void setFixedTautomerState(boolean fixTautomerState) {
        this.fixTautomerState = fixTautomerState;
    }

    /**
     * Does not allow for changes to the tautomer states of titrating residues
     */
    public void setFixedTitrationState(boolean fixTitrationState) {
        this.fixTitrationState = fixTitrationState;
    }

    /**
     * Reset initialized lambdas to a naive guess based on the model pKa for each extended residue
     */
    public void reGuessLambdas() {
        logger.info(" Reinitializing lambdas to match pH");
        for (Residue residue : titratingResidueList) {
            double lambda = initialTitrationState(residue, 1.0, true);
            setTitrationLambda(residue, lambda);
            double tautomerLambda = (int) Math.round(random());
            setTautomerLambda(residue, tautomerLambda);
        }
    }

    /**
     * Set the tautomer lambda of a residue and update corresponding theta
     *
     * @param residue
     * @param lambda
     */
    public void setTautomerLambda(Residue residue, double lambda) {
        setTautomerLambda(residue, lambda, true);
    }

    /**
     * Set the tautomer lambda of a residue and update corresponding theta if desired
     *
     * @param residue      residue to set the lambda of
     * @param lambda       value to set the residue to
     * @param changeThetas whether to change the theta positions ~comes with information loss~
     */
    public void setTautomerLambda(Residue residue, double lambda, boolean changeThetas) {
        double[] thetaPosition = esvState.x();
        if (tautomerizingResidueList.contains(residue) && !lockStates) {
            // The correct index in the theta arrays for tautomer coordinates is after the titration list.
            // So titrationList.size() + tautomerIndex should match with appropriate spot in thetaPosition, etc.
            int index = tautomerizingResidueList.indexOf(residue) + nTitr;
            extendedLambdas[index] = lambda;
            if (changeThetas) {
                thetaPosition[index] = Math.asin(Math.sqrt(lambda));
            }
            List<Atom> currentAtomList = residue.getSideChainAtoms();
            for (Atom atom : currentAtomList) {
                int atomIndex = atom.getArrayIndex();
                tautomerLambdas[atomIndex] = lambda;
            }
        } /*else {
            logger.warning(format("This residue %s does not have any titrating tautomers.", residue.getName()));
        }*/
    }

    /**
     * Set the titration lambda of a residue and update corresponding theta
     *
     * @param residue residue to set the lambda of
     * @param lambda  value to set the residue to
     */
    public void setTitrationLambda(Residue residue, double lambda) {
        setTitrationLambda(residue, lambda, true);
    }

    /**
     * Set the titration lambda of a residue and update corresponding theta if desired
     *
     * @param residue      residue to set the lambda of
     * @param lambda       value to set the residue to
     * @param changeThetas whether to change the theta positions ~comes with information loss~
     */
    public void setTitrationLambda(Residue residue, double lambda, boolean changeThetas) {
        double[] thetaPosition = esvState.x();
        if (titratingResidueList.contains(residue) && !lockStates) {
            int index = titratingResidueList.indexOf(residue);
            extendedLambdas[index] = lambda;
            if (changeThetas) {
                thetaPosition[index] = Math.asin(Math.sqrt(lambda));
            }
            List<Atom> currentAtomList = residue.getSideChainAtoms();
            for (Atom atom : currentAtomList) {
                int atomIndex = atom.getArrayIndex();
                titrationLambdas[atomIndex] = lambda;
            }
        }
    }

    /**
     * Overwrites the histogram passed into it and returns the new one out ~output never used?~
     *
     * @param histogram 2D histogram list with the tautomer and titration states compressed to a 1D array
     * @return another compressed histogram
     */
    public int[][] getESVHistogram(int[][] histogram) {
        for (int i = 0; i < titratingResidueList.size(); i++) {
            int h = 0;
            for (int j = 0; j < 10; j++) {
                for (int k = 0; k < 10; k++) {
                    histogram[i][h++] = esvHistogram[i][j][k];
                }
            }
        }
        return histogram;
    }

    /**
     * Changes this ESV's histogram to equal the one passed
     *
     * @param histogram histogram to set this ESV histogram to
     */
    public void copyESVHistogramTo(int[][] histogram) {
        for (int i = 0; i < titratingResidueList.size(); i++) {
            int h = 0;
            for (int j = 0; j < 10; j++) {
                for (int k = 0; k < 10; k++) {
                    esvHistogram[i][j][k] = histogram[i][h++];
                }
            }
        }
    }

    /**
     * Zero out each array element of the vdW ESV array
     */
    public void initEsvVdw() {
        for (int i = 0; i < nESVs; i++) {
            esvVdwDerivs[i].set(0.0);
        }
    }

    /**
     * Zero out each array element of the permElec ESV array
     */
    public void initEsvPermElec() {
        for (int i = 0; i < nESVs; i++) {
            esvPermElecDerivs[i].set(0.0);
        }
    }

    /**
     * Zero out each array element of the indElec ESV array
     */
    public void initEsvIndElec() {
        for (int i = 0; i < nESVs; i++) {
            esvIndElecDerivs[i].set(0.0);
        }
    }

    /**
     * get Titration Lambda for an extended atom
     *
     * @param atomIndex
     * @return titrationLambdas[atomIndex]
     */
    public double getTitrationLambda(int atomIndex) {
        return titrationLambdas[atomIndex];
    }

    /**
     * get the index of the extended residue list that corresponds to this atom
     *
     * @param i
     * @return titrationIndexMap[i]
     */
    public int getTitrationESVIndex(int i) {
        return titrationIndexMap[i];
    }

    /**
     * get Tautomer Lambda for an extended atom
     *
     * @param atomIndex
     * @return tautomerLambdas[atomIndex]
     */
    public double getTautomerLambda(int atomIndex) {
        return tautomerLambdas[atomIndex];
    }

    public int getTautomerESVIndex(int i) {
        return tautomerIndexMap[i];
    }

    /**
     * Return the List of Titrating Residues
     *
     * @return titratingResidueList
     */
    public List<Residue> getTitratingResidueList() {
        return titratingResidueList;
    }

    /**
     * Return the List of Tautomerizing Residues
     *
     * @return tautomerizingResidueList
     */
    public List<Residue> getTautomerizingResidueList() {
        return tautomerizingResidueList;
    }

    /**
     * Return the List of Extended Residues which = TitratingResidueList + TautomerizingResidueList
     *
     * @return extendedResidueList
     */
    public List<Residue> getExtendedResidueList() {
        return extendedResidueList;
    }

    public double getThetaMass() {
        return thetaMass;
    }

    public double getThetaFriction() {
        return thetaFriction;
    }

    /**
     * Gets a copy of the array of doubles that matches the nESVs correspoding to each titration and tautomer lambda
     *
     * @return double array of length nESVs
     */
    public double[] getExtendedLambdas() {
        double[] lambdas = new double[nESVs];
        System.arraycopy(extendedLambdas, 0, lambdas, 0, lambdas.length);
        return lambdas;
    }

    /**
     * getLambdaList.
     *
     * @return a {@link String} object.
     */
    public String getLambdaList() {
        if (nESVs < 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nESVs; i++) {
            if (i == 0) {
                sb.append("\n  Titration Lambdas: ");
            }
            if (i > 0) {
                sb.append(", ");
            }
            if (i == nTitr) {
                sb.append("\n  Tautomer Lambdas: ");
            }
            sb.append(format("%6.4f", extendedLambdas[i]));
        }
        return sb.toString();
    }

    /**
     * All atoms of the fully-protonated system (not just those affected by this system).
     *
     * @return an array of {@link Atom} objects.
     */
    public Atom[] getExtendedAtoms() {
        return extendedAtoms;
    }

    /**
     * Companion to getExtendedAtoms() for vdw::setAtoms and pme::setAtoms.
     *
     * @return an array of {@link int} objects.
     */
    public int[] getExtendedMolecule() {
        return extendedMolecules;
    }

    /**
     * Sum up total bias (Ubias = UpH + Udiscr - Umod)
     *
     * @return totalBiasEnergy
     */
    public double getBiasEnergy() {
        double totalBiasEnergy = 0.0;
        double[] biasEnergyComponents = new double[9];
        //Do not double count residues in tautomer list.
        for (Residue residue : titratingResidueList) {
            getBiasTerms(residue, biasEnergyComponents);
            double biasEnergy = biasEnergyComponents[discrBiasIndex] + biasEnergyComponents[pHBiasIndex] + biasEnergyComponents[modelBiasIndex];
            totalBiasEnergy += biasEnergy;
        }
        return totalBiasEnergy;
    }

    /**
     * getBiasDecomposition.
     *
     * @return a {@link String} object.
     */
    public String getBiasDecomposition() {
        double discrBias = 0.0;
        double phBias = 0.0;
        double modelBias = 0.0;
        double[] biasEnergyAndDerivs = new double[9];
        for (Residue residue : titratingResidueList) {
            getBiasTerms(residue, biasEnergyAndDerivs);
            discrBias += biasEnergyAndDerivs[discrBiasIndex];
            phBias += biasEnergyAndDerivs[pHBiasIndex];
            //Reminder: Ubias = UpH + Udiscr - Umod but the Umod terms already have their sign reversed
            modelBias += biasEnergyAndDerivs[modelBiasIndex];
        }
        return format("    %-16s %16.8f\n", "Discretizer", discrBias)
                + format("    %-16s %16.8f\n", "Acidostat", phBias)
                + format("    %-16s %16.8f\n", "Fmod", modelBias);
    }

    /**
     * Calculate prefactor for scaling the van der Waals based on titration/tautomer state if titrating proton
     */
    public void getVdwPrefactor(int atomIndex, double[] vdwPrefactorAndDerivs) {
        double prefactor = 1.0;
        double titrationDeriv = 0.0;
        double tautomerDeriv = 0.0;
        if (!isTitratingHydrogen(atomIndex) || !doVDW) {
            vdwPrefactorAndDerivs[0] = prefactor;
            vdwPrefactorAndDerivs[1] = titrationDeriv;
            vdwPrefactorAndDerivs[2] = tautomerDeriv;
            return;
        }
        AminoAcid3 AA3 = residueNames[atomIndex];
        switch (AA3) {
            case ASD:
            case GLD:
                if (tautomerDirections[atomIndex] == 1) {
                    prefactor = titrationLambdas[atomIndex] * tautomerLambdas[atomIndex];
                    titrationDeriv = tautomerLambdas[atomIndex];
                    tautomerDeriv = titrationLambdas[atomIndex];
                } else if (tautomerDirections[atomIndex] == -1) {
                    prefactor = titrationLambdas[atomIndex] * (1.0 - tautomerLambdas[atomIndex]);
                    titrationDeriv = (1.0 - tautomerLambdas[atomIndex]);
                    tautomerDeriv = -titrationLambdas[atomIndex];
                }
                break;
            case HIS:
                if (tautomerDirections[atomIndex] == 1) {
                    prefactor = (1.0 - titrationLambdas[atomIndex]) * tautomerLambdas[atomIndex] + titrationLambdas[atomIndex];
                    titrationDeriv = -tautomerLambdas[atomIndex] + 1.0;
                    tautomerDeriv = (1 - titrationLambdas[atomIndex]);
                } else if (tautomerDirections[atomIndex] == -1) {
                    prefactor = (1.0 - titrationLambdas[atomIndex]) * (1.0 - tautomerLambdas[atomIndex]) + titrationLambdas[atomIndex];
                    titrationDeriv = tautomerLambdas[atomIndex];
                    tautomerDeriv = -(1.0 - titrationLambdas[atomIndex]);
                }
                break;
            case LYS:
            case CYS:
                prefactor = titrationLambdas[atomIndex];
                titrationDeriv = 1.0;
                tautomerDeriv = 0.0;
                break;
        }
        vdwPrefactorAndDerivs[0] = prefactor;
        vdwPrefactorAndDerivs[1] = titrationDeriv;
        vdwPrefactorAndDerivs[2] = tautomerDeriv;
    }

    /**
     * Questions whether the current hydrogen's polarizability is changing in response to lambda being updated.
     *
     * @param atomIndex
     * @return
     */
    public boolean isTitratingHydrogen(int atomIndex) {
        return isTitratingHydrogen[atomIndex];
    }

    /**
     * Add van der Waals deriv to appropriate dU/dL term given the atom index and its contributions.
     *
     * @param atomI
     * @param vdwEnergy
     * @param vdwPrefactorAndDerivI
     * @param vdwPrefactorJ
     */
    public void addVdwDeriv(int atomI, double vdwEnergy, double[] vdwPrefactorAndDerivI, double vdwPrefactorJ) {
        if (!isTitratingHydrogen(atomI)) {
            return;
        }

        //Sum up dU/dL for titration ESV if atom i is titrating hydrogen
        //Sum up dU/dL for tautomer ESV if atom i is titrating hydrogen
        int titrationEsvIndex = titrationIndexMap[atomI];
        int tautomerEsvIndex = tautomerIndexMap[atomI] + nTitr;// tautomerIndexMap[atomI] = -1 for non tautomerizing residues
        double dTitr_dLambda;
        double dTaut_dLambda;

        dTitr_dLambda = vdwPrefactorAndDerivI[1] * vdwPrefactorJ * vdwEnergy;
        dTaut_dLambda = vdwPrefactorAndDerivI[2] * vdwPrefactorJ * vdwEnergy;

        esvVdwDerivs[titrationEsvIndex].addAndGet(dTitr_dLambda);
        if (tautomerEsvIndex >= nTitr) {
            esvVdwDerivs[tautomerEsvIndex].addAndGet(dTaut_dLambda);
        }
    }

    /**
     * Add Perm Elec deriv to appropriate dU/dL term given the atom index and its contributions.
     *
     * @param atomI
     * @param titrationEnergy
     * @param tautomerEnergy
     */
    public void addPermElecDeriv(int atomI, double titrationEnergy, double tautomerEnergy) {
        int titrationEsvIndex = titrationIndexMap[atomI];
        int tautomerEsvIndex = tautomerIndexMap[atomI] + nTitr;// tautomerIndexMap[atomI] = -1 for non tautomerizing residues
        esvPermElecDerivs[titrationEsvIndex].addAndGet(titrationEnergy);
        if (tautomerEsvIndex >= nTitr) {
            esvPermElecDerivs[tautomerEsvIndex].addAndGet(tautomerEnergy);
        }
    }

    /**
     * Add Induced Elec deriv to appropriate dU/dL term given the atom index and its contributions.
     *
     * @param atomI
     * @param titrationEnergy
     * @param tautomerEnergy
     */
    public void addIndElecDeriv(int atomI, double titrationEnergy, double tautomerEnergy) {
        int titrationEsvIndex = titrationIndexMap[atomI];
        int tautomerEsvIndex = tautomerIndexMap[atomI] + nTitr; // tautomerIndexMap[atomI] = -1 for non tautomerizing residues
        esvIndElecDerivs[titrationEsvIndex].addAndGet(titrationEnergy);
        if (tautomerEsvIndex >= nTitr) {
            esvIndElecDerivs[tautomerEsvIndex].addAndGet(tautomerEnergy);
        }
    }

    /**
     * getConstantPh.
     *
     * @return a double.
     */
    public double getConstantPh() {
        return constantSystemPh;
    }

    /**
     * setConstantPh.
     *
     * @param pH a double.
     */
    public void setConstantPh(double pH) {
        constantSystemPh = pH;
    }

    /**
     * @return titrationUtils master copy from ExtendedSystem.
     */
    public TitrationUtils getTitrationUtils() {
        return titrationUtils;
    }

    /**
     * Sets the restartFile field of this extended system to the passed file. This does not read the file, it only
     * determines where the writeRestartFile() will write to.
     *
     * @param esvFile
     */
    public void setRestartFile(File esvFile) {
        restartFile = esvFile;
    }

    /**
     * Writes out the current state of the extended system to the specified file without setting the file to that location.
     *
     * @param esvFile file to be written to
     * @return whether the read was successful or not
     */
    public boolean writeESVInfoTo(File esvFile) {
        logger.info("Writing pH Dynamics out to: " + esvFile.getParentFile().getName() + File.separator + esvFile.getName());
        double[] thetaPosition = esvState.x();
        double[] thetaVelocity = esvState.v();
        double[] thetaAccel = esvState.a();
        return esvFilter.writeESV(esvFile, thetaPosition, thetaVelocity, thetaAccel, titratingResidueList, esvHistogram, constantSystemPh);
    }

    /**
     * Method overwrites whatever is in the extended system at the time with the read data.
     * <p>
     * CAUTION: If the old data is not written out to file before this is called, the data will be lost.
     *
     * @param esvFile esvFile to read
     * @return whether the read was successful or not
     */
    public boolean readESVInfoFrom(File esvFile) {
        double[] thetaPosition = esvState.x();
        double[] thetaVelocity = esvState.v();
        double[] thetaAccel = esvState.a();
        return esvFilter.readESV(esvFile, thetaPosition, thetaVelocity, thetaAccel, esvHistogram);
    }

    /**
     * setTemperature.
     *
     * @param set a double.
     */
    public void setTemperature(double set) {
        currentTemperature = set;
    }

    /**
     * Processes lambda values based on propagation of theta value from Stochastic integrator in Molecular dynamics
     */
    public void preForce() {
        updateLambdas();
    }

    /**
     * Execute writeESV from esvFilter that is contained within ExtendedSystem
     */
    public void writeRestart() {
        String esvName = FileUtils.relativePathTo(restartFile).toString();
        double[] thetaPosition = esvState.x();
        double[] thetaVelocity = esvState.v();
        double[] thetaAccel = esvState.a();
        if (esvFilter.writeESV(restartFile, thetaPosition, thetaVelocity, thetaAccel, titratingResidueList, esvHistogram, constantSystemPh)) {
            logger.info(" Wrote PhDynamics restart file to " + esvName);
        } else {
            logger.info(" Writing PhDynamics restart file to " + esvName + " failed");
        }
    }

    /**
     * Execute writeLambdaHistogrm from esvFilter that is contained within ExtendedSystem
     * Prints the ESV histogram for each titrating residue
     */
    public void writeLambdaHistogram(boolean printHistograms) {
        printProtonationRatios();
        if (printHistograms) {
            logger.info(esvFilter.getLambdaHistogram(titratingResidueList, esvHistogram, constantSystemPh));
        }
    }

    /**
     * Calculate the Deprotonation Fraction from the ESV histogram
     */
    public void printProtonationRatios() {
        for (int i = 0; i < esvHistogram.length; i++) {
            int[] rowSums = new int[esvHistogram[i].length];
            for (int j = 0; j < esvHistogram[i].length; j++) {
                for (int k = 0; k < esvHistogram[i][j].length; k++) {
                    rowSums[j] += esvHistogram[i][j][k];
                }
            }
            int i1 = rowSums[0] + rowSums[rowSums.length - 1];
            double buf = i1 == 0 ? 0.0 : .001;
            logger.info(" " + extendedResidueList.get(i).toString() + " Deprotonation Fraction at pH " + constantSystemPh + ": " + (rowSums[0] / (i1 + buf)));
            if (buf == 0.0) {
                logger.info(" Buffer required to avoid division by 0");
            }
        }
    }

    @Override
    public double[] getAcceleration(double[] acceleration) {
        return getThetaAccel();
    }

    public double[] getThetaAccel() {
        return esvState.a();
    }

    @Override
    public double energyAndGradient(double[] x, double[] g) {
        double[] thetaPosition = esvState.x();
        System.arraycopy(x, 0, thetaPosition, 0, thetaPosition.length);
        updateLambdas();
        fillESVgradient(g);
        return energy(x);
    }

    @Override
    public List<Constraint> getConstraints() {
        return constraints.isEmpty() ? Collections.emptyList() : new ArrayList<>(constraints);
    }

    @Override
    public STATE getEnergyTermState() {
        return null;
    }

    private double[] fillESVgradient(double[] g) {
        double[] gradESV = postForce();
        System.arraycopy(gradESV, 0, g, 0, g.length);
        return g;
    }

    @Override
    public void setEnergyTermState(STATE state) {

    }

    @Override
    public double[] getMass() {
        return getThetaMassArray();
    }

    /**
     * Applies a chain rule term to the derivative to account for taking a derivative of lambda = sin(theta)^2
     *
     * @return dE/dL a double[]
     */
    public double[] postForce() {
        double[] thetaPosition = esvState.x();
        double[] dEdL = ExtendedSystem.this.getDerivatives();
        double[] dEdTheta = new double[dEdL.length];
        for (int i = 0; i < nESVs; i++) {
            dEdTheta[i] = dEdL[i] * sin(2 * thetaPosition[i]);
            //logger.info("dEdL["+i+"]: "+dEdL[i]);
        }
        return dEdTheta;
    }

    public double[] getThetaMassArray() {
        return esvState.getMass();
    }

    @Override
    public double[] getPreviousAcceleration(double[] previousAcceleration) {
        return new double[0];
    }
    @Override
    public double energy(double[] x) {
        double[] coords = new double[forceFieldEnergy.getNumberOfVariables()];
        forceFieldEnergy.getCoordinates(coords);
        return forceFieldEnergy.energy(coords);
    }

    @Override
    public VARIABLE_TYPE[] getVariableTypes() {
        return new VARIABLE_TYPE[0];
    }

    @Override
    public double[] getVelocity(double[] velocity) {
        return getThetaVelocity();
    }

    public double[] getThetaVelocity() {
        return esvState.v();
    }

    @Override
    public void setAcceleration(double[] acceleration) {

    }
    @Override
    public double[] getCoordinates(double[] parameters) {
        return getThetaPosition();
    }

    @Override
    public void setPreviousAcceleration(double[] previousAcceleration) {

    }

    @Override
    public void setVelocity(double[] velocity) {

    }

    public SystemState getState() {
        return esvState;
    }

    public double[] getThetaPosition() {
        return esvState.x();
    }

    @Override
    public int getNumberOfVariables() {
        return nESVs;
    }

    @Override
    public double[] getScaling() {
        double[] scaling = new double[nESVs];
        Arrays.fill(scaling, 1.0);
        return scaling;
    }

    @Override
    public void setScaling(double[] scaling) {

    }

    @Override
    public double getTotalEnergy() {
        return 0;
    }

}
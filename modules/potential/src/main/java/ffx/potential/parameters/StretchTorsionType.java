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
package ffx.potential.parameters;

import ffx.utilities.FFXProperty;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static ffx.potential.parameters.ForceField.ForceFieldType.STRTORS;
import static ffx.utilities.Constants.ANG_TO_NM;
import static ffx.utilities.Constants.KCAL_TO_KJ;
import static ffx.utilities.PropertyGroup.EnergyUnitConversion;
import static ffx.utilities.PropertyGroup.PotentialFunctionParameter;
import static java.lang.Double.parseDouble;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.util.Arrays.copyOf;

/**
 * The StretchTorsionType class defines one stretch-torsion energy type.
 *
 * @author Michael J. Schnieders
 * @since 1.0
 */
@FFXProperty(name = "strtors", clazz = String.class, propertyGroup = PotentialFunctionParameter, description = """
    [2 integers and 1 real]
    Provides the values for a single stretch-torsion cross term potential parameter.
    The two integer modifiers give the atom class numbers for the atoms involved in the central bond of the torsional angles to be parameterized.
    The real modifier gives the value of the stretch-torsion force constant for all torsional angles with the defined atom classes for the central bond.
    The default units for the stretch-torsion force constant can be controlled via the strtorunit keyword.
    """)
public final class StretchTorsionType extends BaseType implements Comparator<String> {

  public static final double DEFAULT_STRTOR_UNIT = 1.0;

  /**
   * Unit conversion.
   */
  @FFXProperty(name = "strtorunit", propertyGroup = EnergyUnitConversion, defaultValue = "1.0", description = """
      Sets the scale factor needed to convert the energy value computed by the bond stretching-torsional
      angle cross term potential into units of kcal/mole. The correct value is force field dependent and typically
      provided in the header of the master force field parameter file.
      """)
  public double strTorUnit = DEFAULT_STRTOR_UNIT;

  /**
   * A Logger for the StretchTorsionType class.
   */
  private static final Logger logger = Logger.getLogger(StretchTorsionType.class.getName());
  /**
   * Atom classes for this stretch-torsion type.
   */
  public final int[] atomClasses;
  /**
   * Force constants.
   */
  public final double[] forceConstants;

  /**
   * StretchTorsionType Constructor.
   *
   * @param atomClasses    Atom classes.
   * @param forceConstants Force constant.
   */
  public StretchTorsionType(int[] atomClasses, double[] forceConstants) {
    // Pass the key from sorted classes to the super constructor.
    super(STRTORS, sortKey(atomClasses));
    this.atomClasses = atomClasses;
    this.forceConstants = forceConstants;
  }

  /**
   * average.
   *
   * @param stretchTorsionType1 a {@link ffx.potential.parameters.StretchTorsionType} object.
   * @param stretchTorsionType2 a {@link ffx.potential.parameters.StretchTorsionType} object.
   * @param atomClasses         an array of {@link int} objects.
   * @return a {@link ffx.potential.parameters.StretchTorsionType} object.
   */
  public static StretchTorsionType average(StretchTorsionType stretchTorsionType1,
                                           StretchTorsionType stretchTorsionType2, int[] atomClasses) {
    if (stretchTorsionType1 == null || stretchTorsionType2 == null || atomClasses == null) {
      return null;
    }
    int len = stretchTorsionType1.forceConstants.length;
    if (len != stretchTorsionType2.forceConstants.length) {
      return null;
    }
    double[] forceConstants = new double[len];
    for (int i = 0; i < len; i++) {
      forceConstants[i] =
          (stretchTorsionType1.forceConstants[i] + stretchTorsionType2.forceConstants[i]) / 2.0;
    }
    return new StretchTorsionType(atomClasses, forceConstants);
  }

  /**
   * Construct an StretchTorsionType from an input string.
   *
   * @param input  The overall input String.
   * @param tokens The input String tokenized.
   * @return an StretchTorsionType instance.
   */
  public static StretchTorsionType parse(String input, String[] tokens) {
    if (tokens.length < 13) {
      logger.log(Level.WARNING, "Invalid STRTORS type:\n{0}", input);
    } else {
      try {
        int[] atomClasses = new int[4];
        atomClasses[0] = parseInt(tokens[1]);
        atomClasses[1] = parseInt(tokens[2]);
        atomClasses[2] = parseInt(tokens[3]);
        atomClasses[3] = parseInt(tokens[4]);
        double[] constants = new double[9];
        constants[0] = parseDouble(tokens[5]);
        constants[1] = parseDouble(tokens[6]);
        constants[2] = parseDouble(tokens[7]);
        constants[3] = parseDouble(tokens[8]);
        constants[4] = parseDouble(tokens[9]);
        constants[5] = parseDouble(tokens[10]);
        constants[6] = parseDouble(tokens[11]);
        constants[7] = parseDouble(tokens[12]);
        constants[8] = parseDouble(tokens[13]);
        return new StretchTorsionType(atomClasses, constants);
      } catch (NumberFormatException e) {
        String message = "Exception parsing STRTORS type:\n" + input + "\n";
        logger.log(Level.SEVERE, message, e);
      }
    }
    return null;
  }

  /**
   * This method sorts the atom classes for the torsion.
   *
   * @param c atomClasses
   * @return lookup key
   * @since 1.0
   */
  public static String sortKey(int[] c) {
    return c[0] + " " + c[1] + " " + c[2] + " " + c[3];
  }

  /**
   * {@inheritDoc}
   *
   * @since 1.0
   */
  @Override
  public int compare(String s1, String s2) {
    String[] keys1 = s1.split(" ");
    String[] keys2 = s2.split(" ");
    int[] c1 = new int[4];
    int[] c2 = new int[4];

    for (int i = 0; i < 4; i++) {
      c1[i] = parseInt(keys1[i]);
      c2[i] = parseInt(keys2[i]);
    }

    if (c1[1] < c2[1]) {
      return -1;
    } else if (c1[1] > c2[1]) {
      return 1;
    } else if (c1[2] < c2[2]) {
      return -1;
    } else if (c1[2] > c2[2]) {
      return 1;
    } else if (c1[0] < c2[0]) {
      return -1;
    } else if (c1[0] > c2[0]) {
      return 1;
    } else if (c1[3] < c2[3]) {
      return -1;
    } else if (c1[3] > c2[3]) {
      return 1;
    }

    return 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    StretchTorsionType stretchTorsionType = (StretchTorsionType) o;
    return Arrays.equals(atomClasses, stretchTorsionType.atomClasses);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int hashCode() {
    return Arrays.hashCode(atomClasses);
  }

  /**
   * Increment the atom classes by a specified amount.
   *
   * @param increment The increment to add to the atom classes.
   */
  public void incrementClasses(int increment) {
    for (int i = 0; i < atomClasses.length; i++) {
      atomClasses[i] += increment;
    }
    setKey(sortKey(atomClasses));
  }

  /**
   * Remap new atom classes to known internal ones.
   *
   * @param typeMap a lookup between new atom types and known atom types.
   * @return a {@link ffx.potential.parameters.StretchTorsionType} object.
   */
  public StretchTorsionType patchClasses(HashMap<AtomType, AtomType> typeMap) {
    int count = 0;
    int len = atomClasses.length;

    // Check if this Type contain 1 or 2 mapped atom classes.
    for (AtomType newType : typeMap.keySet()) {
      for (int atomClass : atomClasses) {
        if (atomClass == newType.atomClass) {
          count++;
        }
      }
    }

    // If found, create a new StretchTorsionType that bridges to known classes.
    if (count == 1 || count == 2) {
      int[] newClasses = copyOf(atomClasses, len);
      for (AtomType newType : typeMap.keySet()) {
        for (int i = 0; i < len; i++) {
          if (atomClasses[i] == newType.atomClass) {
            AtomType knownType = typeMap.get(newType);
            newClasses[i] = knownType.atomClass;
          }
        }
      }
      return new StretchTorsionType(newClasses, forceConstants);
    }
    return null;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Nicely formatted Stretch-Torsion string.
   */
  @Override
  public String toString() {
    return format(
        "strtors  %5d  %5d  %5d  %5d  %6.3f  %6.3f  %6.3f  %6.3f  %6.3f  %6.3f  %6.3f  %6.3f  %6.3f",
        atomClasses[0], atomClasses[1], atomClasses[2], atomClasses[3], forceConstants[0],
        forceConstants[1], forceConstants[2], forceConstants[3], forceConstants[4],
        forceConstants[5], forceConstants[6], forceConstants[7], forceConstants[8]);
  }

  /**
   * Create an AmoebaStretchTorsionForce Element.
   *
   * @param doc        the Document instance.
   * @param forceField the ForceField instance to grab constants from.
   * @return the AmoebaStretchTorsionForce Element.
   */
  public static Element getXMLForce(Document doc, ForceField forceField) {
    Map<String, StretchTorsionType> types = forceField.getStretchTorsionTypes();
    if (!types.values().isEmpty()) {
      Element node = doc.createElement("AmoebaStretchTorsionForce");
      for (StretchTorsionType stretchTorsionType : types.values()) {
        node.appendChild(stretchTorsionType.toXML(doc));
      }
      return node;
    }
    return null;
  }

  /**
   * Write StretchTorsionType to OpenMM XML format.
   *
   * @param doc the Document instance.
   * @return the Torsion element.
   */
  public Element toXML(Document doc) {
    Element node = doc.createElement("Torsion");
    node.setAttribute("class1", format("%d", atomClasses[0]));
    node.setAttribute("class2", format("%d", atomClasses[1]));
    node.setAttribute("class3", format("%d", atomClasses[2]));
    node.setAttribute("class4", format("%d", atomClasses[3]));
    node.setAttribute("v11", format("%.17f", forceConstants[0] * KCAL_TO_KJ / ANG_TO_NM));
    node.setAttribute("v12", format("%.17f", forceConstants[1] * KCAL_TO_KJ / ANG_TO_NM));
    node.setAttribute("v13", format("%.17f", forceConstants[2] * KCAL_TO_KJ / ANG_TO_NM));
    node.setAttribute("v21", format("%.17f", forceConstants[3] * KCAL_TO_KJ / ANG_TO_NM));
    node.setAttribute("v22", format("%.17f", forceConstants[4] * KCAL_TO_KJ / ANG_TO_NM));
    node.setAttribute("v23", format("%.17f", forceConstants[5] * KCAL_TO_KJ / ANG_TO_NM));
    node.setAttribute("v31", format("%.17f", forceConstants[6] * KCAL_TO_KJ / ANG_TO_NM));
    node.setAttribute("v32", format("%.17f", forceConstants[7] * KCAL_TO_KJ / ANG_TO_NM));
    node.setAttribute("v33", format("%.17f", forceConstants[8] * KCAL_TO_KJ / ANG_TO_NM));
    return node;
  }
}
